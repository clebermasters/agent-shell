//! Short-lived, phone-reported network metadata and wireless ADB endpoints.
//! Reporting never establishes reachability or authorizes an ADB connection.
use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use axum::{
    extract::Path,
    http::{header::CACHE_CONTROL, HeaderValue, StatusCode},
    middleware,
    response::Response,
    routing::get,
    Json, Router,
};
use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;
use uuid::Uuid;

const ENDPOINT_TTL: Duration = Duration::from_secs(90);
const MAX_DEVICES: usize = 128;
const MAX_NETWORKS: usize = 16;
const MAX_NETWORK_ADDRESSES: usize = 16;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceAdbReport {
    pub device_id: String,
    pub device_name: String,
    pub host: Option<String>,
    pub port: Option<u16>,
    #[serde(default)]
    pub networks: Option<Vec<DeviceNetwork>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DeviceNetworkTransport {
    Wifi,
    Cellular,
    Vpn,
    Ethernet,
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceNetwork {
    pub transport: DeviceNetworkTransport,
    pub interface: String,
    pub addresses: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceAdbEndpoint {
    host: String,
    port: u16,
    address: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DeviceAdbStatus {
    Available,
    Unavailable,
    Offline,
    Stale,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DeviceNetworkStatus {
    Current,
    Unknown,
    Offline,
    Stale,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceAdbSnapshot {
    device_id: String,
    device_name: String,
    status: DeviceAdbStatus,
    endpoint: Option<DeviceAdbEndpoint>,
    network_status: DeviceNetworkStatus,
    networks: Vec<DeviceNetwork>,
    updated_at: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceNetworkSnapshot {
    device_id: String,
    device_name: String,
    network_status: DeviceNetworkStatus,
    networks: Vec<DeviceNetwork>,
    updated_at: u64,
}

impl From<DeviceAdbSnapshot> for DeviceNetworkSnapshot {
    fn from(snapshot: DeviceAdbSnapshot) -> Self {
        Self {
            device_id: snapshot.device_id,
            device_name: snapshot.device_name,
            network_status: snapshot.network_status,
            networks: snapshot.networks,
            updated_at: snapshot.updated_at,
        }
    }
}

#[derive(Serialize)]
struct DeviceList {
    devices: Vec<DeviceAdbSnapshot>,
}

struct DeviceRecord {
    device_name: String,
    endpoint: Option<DeviceAdbEndpoint>,
    networks: Option<Vec<DeviceNetwork>>,
    owner: String,
    connected: bool,
    reported_at: Instant,
    updated_at: u64,
}

impl DeviceRecord {
    fn is_stale(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.reported_at) > ENDPOINT_TTL
    }

    fn snapshot(&self, device_id: &str, now: Instant) -> DeviceAdbSnapshot {
        let status = if !self.connected {
            DeviceAdbStatus::Offline
        } else if self.is_stale(now) {
            DeviceAdbStatus::Stale
        } else if self.endpoint.is_none() {
            DeviceAdbStatus::Unavailable
        } else {
            DeviceAdbStatus::Available
        };
        let network_status = if !self.connected {
            DeviceNetworkStatus::Offline
        } else if self.is_stale(now) {
            DeviceNetworkStatus::Stale
        } else if self.networks.is_none() {
            DeviceNetworkStatus::Unknown
        } else {
            DeviceNetworkStatus::Current
        };
        DeviceAdbSnapshot {
            device_id: device_id.to_string(),
            device_name: self.device_name.clone(),
            status,
            endpoint: (status == DeviceAdbStatus::Available)
                .then(|| self.endpoint.clone())
                .flatten(),
            network_status,
            networks: if network_status == DeviceNetworkStatus::Current {
                self.networks.clone().unwrap_or_default()
            } else {
                Vec::new()
            },
            updated_at: self.updated_at,
        }
    }
}

#[derive(Default)]
pub struct DeviceAdbRegistry {
    devices: RwLock<HashMap<String, DeviceRecord>>,
}

impl DeviceAdbRegistry {
    pub async fn report(&self, owner: &str, report: DeviceAdbReport) -> Result<(), &'static str> {
        self.report_at(
            owner,
            report,
            Instant::now(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
        )
        .await
    }

    async fn report_at(
        &self,
        owner: &str,
        report: DeviceAdbReport,
        now: Instant,
        updated_at: u64,
    ) -> Result<(), &'static str> {
        let device_id = normalize_device_id(&report.device_id)?;
        let device_name = report.device_name.trim();
        if device_name.is_empty()
            || device_name.len() > 128
            || device_name.chars().any(char::is_control)
        {
            return Err("deviceName must contain 1 to 128 bytes without control characters");
        }
        let endpoint = validate_endpoint(report.host.as_deref(), report.port)?;
        let networks = report.networks.map(validate_networks).transpose()?;
        let mut devices = self.devices.write().await;
        if !devices.contains_key(&device_id) && devices.len() >= MAX_DEVICES {
            let evict = devices
                .iter()
                .filter(|(_, record)| !record.connected || record.is_stale(now))
                .min_by_key(|(_, record)| record.reported_at)
                .map(|(id, _)| id.clone());
            if let Some(id) = evict {
                devices.remove(&id);
            } else {
                return Err("device registry is full");
            }
        }
        devices.insert(
            device_id,
            DeviceRecord {
                device_name: device_name.to_string(),
                endpoint,
                networks,
                owner: owner.to_string(),
                connected: true,
                reported_at: now,
                updated_at,
            },
        );
        Ok(())
    }

    /// Only the socket that most recently reported a device can mark it offline.
    pub async fn disconnect(&self, owner: &str) {
        for record in self.devices.write().await.values_mut() {
            if record.owner == owner {
                record.connected = false;
            }
        }
    }

    async fn list(&self) -> Vec<DeviceAdbSnapshot> {
        let devices = self.devices.read().await;
        let now = Instant::now();
        let mut snapshots: Vec<_> = devices
            .iter()
            .map(|(id, record)| record.snapshot(id, now))
            .collect();
        snapshots.sort_by(|left, right| left.device_id.cmp(&right.device_id));
        snapshots
    }

    async fn get(&self, device_id: &str) -> Option<DeviceAdbSnapshot> {
        let device_id = normalize_device_id(device_id).ok()?;
        self.devices
            .read()
            .await
            .get(&device_id)
            .map(|record| record.snapshot(&device_id, Instant::now()))
    }
}

fn normalize_device_id(device_id: &str) -> Result<String, &'static str> {
    if device_id.len() > 36 {
        return Err("deviceId must be a UUID");
    }
    Uuid::parse_str(device_id)
        .map(|id| id.to_string())
        .map_err(|_| "deviceId must be a UUID")
}

fn validate_endpoint(
    host: Option<&str>,
    port: Option<u16>,
) -> Result<Option<DeviceAdbEndpoint>, &'static str> {
    let (host, port) = match (host, port) {
        (None, None) => return Ok(None),
        (Some(host), Some(port)) if port > 0 => (host, port),
        _ => return Err("host and a port from 1 to 65535 must be reported together or both null"),
    };
    let ip = validate_unicast_ip(host)?;
    Ok(Some(DeviceAdbEndpoint {
        host: ip.to_string(),
        port,
        address: SocketAddr::new(ip, port).to_string(),
    }))
}

fn validate_unicast_ip(value: &str) -> Result<IpAddr, &'static str> {
    if value.len() > 45 {
        return Err("address must be a numeric IP address");
    }
    let ip: IpAddr = value
        .parse()
        .map_err(|_| "address must be a numeric IP address")?;
    let routable_ip = match ip {
        IpAddr::V6(ipv6) => ipv6.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(ip),
        _ => ip,
    };
    if routable_ip.is_loopback()
        || routable_ip.is_unspecified()
        || routable_ip.is_multicast()
        || routable_ip == IpAddr::from([255, 255, 255, 255])
    {
        return Err("address must be a unicast device IP address");
    }
    Ok(ip)
}

fn validate_networks(mut networks: Vec<DeviceNetwork>) -> Result<Vec<DeviceNetwork>, &'static str> {
    if networks.len() > MAX_NETWORKS {
        return Err("at most 16 networks may be reported");
    }
    for network in &mut networks {
        if network.interface.is_empty()
            || network.interface.len() > 64
            || !network.interface.bytes().all(|byte| {
                byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.' | b':' | b'-')
            })
        {
            return Err(
                "network interface must contain 1 to 64 ASCII letters, digits, _, ., :, or -",
            );
        }
        if network.addresses.len() > MAX_NETWORK_ADDRESSES {
            return Err("at most 16 addresses per network may be reported");
        }
        let mut canonical = Vec::with_capacity(network.addresses.len());
        for value in &network.addresses {
            let address = validate_unicast_ip(value)?;
            if matches!(address, IpAddr::V6(ipv6) if ipv6.is_unicast_link_local()) {
                return Err("IPv6 link-local network addresses require an interface scope and are not supported");
            }
            let address = address.to_string();
            if !canonical.contains(&address) {
                canonical.push(address);
            }
        }
        network.addresses = canonical;
    }
    Ok(networks)
}

/// Merged into the existing authenticated API router by main.
pub fn api_router<S>(registry: Arc<DeviceAdbRegistry>) -> Router<S>
where
    S: Clone + Send + Sync + 'static,
{
    let list_registry = registry.clone();
    let adb_registry = registry.clone();
    Router::new()
        .route(
            "/api/devices",
            get(move || {
                let registry = list_registry.clone();
                async move {
                    Json(DeviceList {
                        devices: registry.list().await,
                    })
                }
            }),
        )
        .route(
            "/api/devices/:id/adb",
            get(move |Path(device_id): Path<String>| {
                let registry = adb_registry.clone();
                async move {
                    registry
                        .get(&device_id)
                        .await
                        .map(Json)
                        .ok_or(StatusCode::NOT_FOUND)
                }
            }),
        )
        .route(
            "/api/devices/:id/network",
            get(move |Path(device_id): Path<String>| {
                let registry = registry.clone();
                async move {
                    registry
                        .get(&device_id)
                        .await
                        .map(DeviceNetworkSnapshot::from)
                        .map(Json)
                        .ok_or(StatusCode::NOT_FOUND)
                }
            }),
        )
        .layer(middleware::map_response(
            |mut response: Response| async move {
                response
                    .headers_mut()
                    .insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
                response
            },
        ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const DEVICE_ID: &str = "69fd0b51-488c-438e-bdc6-e6470609b7f7";

    fn report(host: Option<&str>, port: Option<u16>) -> DeviceAdbReport {
        DeviceAdbReport {
            device_id: DEVICE_ID.to_string(),
            device_name: "My phone".to_string(),
            host: host.map(str::to_string),
            port,
            networks: None,
        }
    }

    fn networks() -> Vec<DeviceNetwork> {
        vec![
            DeviceNetwork {
                transport: DeviceNetworkTransport::Cellular,
                interface: "rmnet_data0".to_string(),
                addresses: vec![
                    "100.64.1.2".to_string(),
                    "2001:0db8:0:0:0:0:0:1234".to_string(),
                ],
            },
            DeviceNetwork {
                transport: DeviceNetworkTransport::Vpn,
                interface: "tun0".to_string(),
                addresses: vec!["10.8.0.2".to_string()],
            },
        ]
    }

    #[tokio::test]
    async fn cellular_and_vpn_metadata_remain_current_without_an_adb_endpoint() {
        let registry = DeviceAdbRegistry::default();
        let mut device = report(None, None);
        device.networks = Some(networks());
        registry.report("owner", device).await.unwrap();
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.status, DeviceAdbStatus::Unavailable);
        assert!(snapshot.endpoint.is_none());
        assert_eq!(snapshot.network_status, DeviceNetworkStatus::Current);
        assert_eq!(
            snapshot.networks[0].transport,
            DeviceNetworkTransport::Cellular
        );
        assert_eq!(
            snapshot.networks[0].addresses,
            ["100.64.1.2", "2001:db8::1234"]
        );
        assert_eq!(snapshot.networks[1].transport, DeviceNetworkTransport::Vpn);
        assert_eq!(snapshot.networks[1].addresses, ["10.8.0.2"]);
    }

    #[tokio::test]
    async fn network_rotation_clears_lost_networks_and_preserves_new_connection_owner() {
        let registry = DeviceAdbRegistry::default();
        let mut device = report(None, None);
        device.networks = Some(networks());
        registry.report("old", device.clone()).await.unwrap();
        device.networks.as_mut().unwrap().remove(0);
        registry.report("new", device).await.unwrap();
        registry.disconnect("old").await;
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.network_status, DeviceNetworkStatus::Current);
        assert_eq!(snapshot.networks.len(), 1);
        assert_eq!(snapshot.networks[0].transport, DeviceNetworkTransport::Vpn);

        let mut empty = report(None, None);
        empty.networks = Some(Vec::new());
        registry.report("new", empty).await.unwrap();
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.network_status, DeviceNetworkStatus::Current);
        assert!(snapshot.networks.is_empty());

        registry.report("new", report(None, None)).await.unwrap();
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.network_status, DeviceNetworkStatus::Unknown);
        assert!(snapshot.networks.is_empty());
    }

    #[tokio::test]
    async fn stale_and_offline_network_snapshots_hide_addresses() {
        let registry = DeviceAdbRegistry::default();
        let now = Instant::now();
        let mut device = report(Some("192.168.1.2"), Some(37123));
        device.networks = Some(networks());
        registry.report_at("owner", device, now, 123).await.unwrap();
        {
            let devices = registry.devices.read().await;
            let record = devices.get(DEVICE_ID).unwrap();
            let current = record.snapshot(DEVICE_ID, now + ENDPOINT_TTL);
            assert_eq!(current.network_status, DeviceNetworkStatus::Current);
            assert_eq!(current.networks.len(), 2);
            let stale = record.snapshot(DEVICE_ID, now + ENDPOINT_TTL + Duration::from_secs(1));
            assert_eq!(stale.status, DeviceAdbStatus::Stale);
            assert!(stale.endpoint.is_none());
            assert_eq!(stale.network_status, DeviceNetworkStatus::Stale);
            assert!(stale.networks.is_empty());
        }
        registry.disconnect("owner").await;
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.status, DeviceAdbStatus::Offline);
        assert!(snapshot.endpoint.is_none());
        assert_eq!(snapshot.network_status, DeviceNetworkStatus::Offline);
        assert!(snapshot.networks.is_empty());
    }

    #[tokio::test]
    async fn invalid_network_reports_do_not_replace_current_metadata() {
        let registry = DeviceAdbRegistry::default();
        let mut valid = report(None, None);
        valid.networks = Some(networks());
        registry.report("owner", valid.clone()).await.unwrap();
        for value in [
            "127.0.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "::",
            "ff02::1",
            "::ffff:127.0.0.1",
            "fe80::1234",
            "fe80::1234%wlan0",
            "example.com",
            "10.1.2.3/24",
        ] {
            let mut invalid = valid.clone();
            invalid.networks.as_mut().unwrap()[0].addresses = vec![value.to_string()];
            assert!(
                registry.report("owner", invalid).await.is_err(),
                "accepted {value}"
            );
        }
        for interface in [
            "".to_string(),
            "x".repeat(65),
            "tun0;command".to_string(),
            "tun 0".to_string(),
            "réseau".to_string(),
        ] {
            let mut invalid = valid.clone();
            invalid.networks.as_mut().unwrap()[0].interface = interface;
            assert!(registry.report("owner", invalid).await.is_err());
        }
        let mut invalid = valid.clone();
        invalid.networks = Some(vec![networks().remove(0); MAX_NETWORKS + 1]);
        assert!(registry.report("owner", invalid).await.is_err());
        let mut invalid = valid;
        invalid.networks.as_mut().unwrap()[0].addresses =
            vec!["10.1.2.3".to_string(); MAX_NETWORK_ADDRESSES + 1];
        assert!(registry.report("owner", invalid).await.is_err());
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.network_status, DeviceNetworkStatus::Current);
        assert_eq!(snapshot.networks.len(), 2);
        assert_eq!(
            snapshot.networks[0].addresses,
            ["100.64.1.2", "2001:db8::1234"]
        );
    }

    #[tokio::test]
    async fn rotation_replaces_endpoint_and_old_socket_cannot_disconnect_new_owner() {
        let registry = DeviceAdbRegistry::default();
        registry
            .report("old", report(Some("192.168.1.2"), Some(37123)))
            .await
            .unwrap();
        registry
            .report("new", report(Some("192.168.1.3"), Some(41234)))
            .await
            .unwrap();
        registry.disconnect("old").await;
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.status, DeviceAdbStatus::Available);
        assert_eq!(snapshot.endpoint.unwrap().address, "192.168.1.3:41234");
        registry.disconnect("new").await;
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.status, DeviceAdbStatus::Offline);
        assert!(snapshot.endpoint.is_none());
    }

    #[tokio::test]
    async fn stale_and_explicit_unavailable_reports_hide_previous_endpoint() {
        let registry = DeviceAdbRegistry::default();
        let now = Instant::now();
        registry
            .report_at("owner", report(Some("192.168.1.2"), Some(37123)), now, 123)
            .await
            .unwrap();
        {
            let devices = registry.devices.read().await;
            let record = devices.get(DEVICE_ID).unwrap();
            assert!(record
                .snapshot(DEVICE_ID, now + ENDPOINT_TTL)
                .endpoint
                .is_some());
            let stale = record.snapshot(DEVICE_ID, now + ENDPOINT_TTL + Duration::from_secs(1));
            assert_eq!(stale.status, DeviceAdbStatus::Stale);
            assert!(stale.endpoint.is_none());
            assert_eq!(stale.updated_at, 123);
        }
        registry.report("owner", report(None, None)).await.unwrap();
        let snapshot = registry.get(DEVICE_ID).await.unwrap();
        assert_eq!(snapshot.status, DeviceAdbStatus::Unavailable);
        assert!(snapshot.endpoint.is_none());
    }

    #[tokio::test]
    async fn invalid_reports_do_not_overwrite_last_valid_endpoint() {
        let registry = DeviceAdbRegistry::default();
        registry
            .report("owner", report(Some("192.168.1.2"), Some(37123)))
            .await
            .unwrap();
        for (host, port) in [
            (Some("127.0.0.1"), Some(1234)),
            (Some("0.0.0.0"), Some(1234)),
            (Some("224.0.0.1"), Some(1234)),
            (Some("::1"), Some(1234)),
            (Some("::"), Some(1234)),
            (Some("ff02::1"), Some(1234)),
            (Some("::ffff:127.0.0.1"), Some(1234)),
            (Some("example.com"), Some(1234)),
            (Some("192.168.1.2"), Some(0)),
            (Some("192.168.1.2"), None),
            (None, Some(1234)),
        ] {
            assert!(registry.report("owner", report(host, port)).await.is_err());
        }
        let mut invalid = report(None, None);
        invalid.device_id = "not-a-device-uuid".to_string();
        assert!(registry.report("owner", invalid).await.is_err());
        for name in ["".to_string(), "x".repeat(129), "phone\nname".to_string()] {
            let mut invalid = report(None, None);
            invalid.device_name = name;
            assert!(registry.report("owner", invalid).await.is_err());
        }
        assert_eq!(
            registry
                .get(DEVICE_ID)
                .await
                .unwrap()
                .endpoint
                .unwrap()
                .port,
            37123
        );
    }

    #[tokio::test]
    async fn capacity_is_bounded_and_disconnected_records_can_be_replaced() {
        let registry = DeviceAdbRegistry::default();
        let now = Instant::now();
        for index in 0..MAX_DEVICES {
            let mut device = report(None, None);
            device.device_id = Uuid::from_u128(index as u128).to_string();
            registry
                .report_at(&format!("owner-{index}"), device, now, 123)
                .await
                .unwrap();
        }
        assert!(registry
            .report_at("new", report(None, None), now, 123)
            .await
            .is_err());
        registry.disconnect("owner-0").await;
        registry
            .report_at("new", report(None, None), now, 123)
            .await
            .unwrap();
        assert_eq!(registry.devices.read().await.len(), MAX_DEVICES);
        assert!(registry.get(&Uuid::nil().to_string()).await.is_none());
    }

    #[test]
    fn report_wire_shape_and_ipv6_address() {
        let message: crate::types::WebSocketMessage = serde_json::from_value(json!({
            "type": "report-device-adb", "deviceId": DEVICE_ID, "deviceName": "Phone",
            "host": "fd00::1234", "port": 37123
        }))
        .unwrap();
        match message {
            crate::types::WebSocketMessage::ReportDeviceAdb { report } => {
                assert_eq!(report.device_id, DEVICE_ID);
                assert!(report.networks.is_none(), "old reports must remain valid");
                assert_eq!(
                    validate_endpoint(report.host.as_deref(), report.port)
                        .unwrap()
                        .unwrap()
                        .address,
                    "[fd00::1234]:37123"
                );
            }
            _ => panic!("expected report-device-adb"),
        }
        for port in [json!(-1), json!(65536), json!("37123")] {
            assert!(
                serde_json::from_value::<crate::types::WebSocketMessage>(json!({
                    "type": "report-device-adb", "deviceId": DEVICE_ID, "deviceName": "Phone",
                    "host": "192.168.1.2", "port": port
                }))
                .is_err()
            );
        }
    }

    #[tokio::test]
    async fn http_routes_return_documented_json_and_not_found() {
        let registry = Arc::new(DeviceAdbRegistry::default());
        let mut device_report = report(Some("192.168.1.2"), Some(37123));
        device_report.networks = Some(networks());
        registry.report("owner", device_report).await.unwrap();
        let router: Router = api_router(registry);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let client = reqwest::Client::new();
        let list_response = client
            .get(format!("http://{address}/api/devices"))
            .send()
            .await
            .unwrap();
        assert_eq!(
            list_response.headers().get("cache-control").unwrap(),
            "no-store"
        );
        let list: serde_json::Value = list_response.json().await.unwrap();
        let device_response = client
            .get(format!("http://{address}/api/devices/{DEVICE_ID}/adb"))
            .send()
            .await
            .unwrap();
        assert_eq!(
            device_response.headers().get("cache-control").unwrap(),
            "no-store"
        );
        let device: serde_json::Value = device_response.json().await.unwrap();
        let network_response = client
            .get(format!("http://{address}/api/devices/{DEVICE_ID}/network"))
            .send()
            .await
            .unwrap();
        assert_eq!(
            network_response.headers().get("cache-control").unwrap(),
            "no-store"
        );
        let network: serde_json::Value = network_response.json().await.unwrap();
        let missing = client
            .get(format!("http://{address}/api/devices/{}/adb", Uuid::nil()))
            .send()
            .await
            .unwrap();
        assert_eq!(missing.headers().get("cache-control").unwrap(), "no-store");
        let missing_network = client
            .get(format!(
                "http://{address}/api/devices/{}/network",
                Uuid::nil()
            ))
            .send()
            .await
            .unwrap();
        assert_eq!(
            missing_network.headers().get("cache-control").unwrap(),
            "no-store"
        );
        server.abort();
        assert_eq!(list["devices"], json!([device]));
        assert_eq!(device["deviceId"], DEVICE_ID);
        assert_eq!(device["status"], "available");
        assert_eq!(device["networkStatus"], "current");
        assert_eq!(device["networks"].as_array().unwrap().len(), 2);
        assert_eq!(
            network,
            json!({
                "deviceId": DEVICE_ID,
                "deviceName": "My phone",
                "networkStatus": "current",
                "networks": device["networks"],
                "updatedAt": device["updatedAt"],
            })
        );
        assert_eq!(
            device["endpoint"],
            json!({"host": "192.168.1.2", "port": 37123, "address": "192.168.1.2:37123"})
        );
        assert!(device["updatedAt"].as_u64().unwrap() > 0);
        assert_eq!(missing.status().as_u16(), 404);
        assert_eq!(missing_network.status().as_u16(), 404);
    }
}
