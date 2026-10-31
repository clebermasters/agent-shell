//! Read-only process identity shared by native conversation discovery and hooks.
//! Linux keeps its existing start-tick identity; Darwin uses the kernel's
//! microsecond process birth time. Missing permissions never select a guess.
use anyhow::{ensure, Context, Result};
use serde::Serialize;
use std::{collections::HashMap, path::PathBuf};

const MAX_PROCESSES: usize = 262_144;
const MAX_ARGUMENT_BYTES: usize = 4 * 1024 * 1024;
const MAX_OPEN_FILES: usize = 65_536;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProcessInfo {
    pub pid: u32,
    pub parent_pid: u32,
    pub process_group: i32,
    pub foreground_group: i32,
    pub has_tty: bool,
    pub name: String,
    pub argv: Vec<String>,
    pub cwd: Option<PathBuf>,
    pub generation: u64,
    pub started_at_epoch_ms: Option<i64>,
}

fn valid_pid(pid: u32) -> Result<()> {
    ensure!(
        pid > 0 && pid <= i32::MAX as u32,
        "Invalid process identifier"
    );
    Ok(())
}

pub(crate) fn snapshot(pid: u32) -> Result<ProcessInfo> {
    valid_pid(pid)?;
    let value = platform::snapshot(pid)?;
    ensure!(
        platform::generation(pid)? == value.generation,
        "Process changed during observation"
    );
    Ok(value)
}

pub(crate) fn generation(pid: u32) -> Result<u64> {
    valid_pid(pid)?;
    platform::generation(pid)
}

pub(crate) fn name(pid: u32) -> Option<String> {
    valid_pid(pid).ok()?;
    platform::name(pid).ok()
}

pub(crate) fn argv(pid: u32) -> Result<Vec<String>> {
    valid_pid(pid)?;
    let before = generation(pid)?;
    let result = platform::argv(pid)?;
    ensure!(
        generation(pid)? == before,
        "Process changed while reading arguments"
    );
    Ok(result)
}

pub(crate) fn cwd(pid: u32) -> Option<PathBuf> {
    valid_pid(pid).ok()?;
    let before = generation(pid).ok()?;
    let result = platform::cwd(pid)?;
    (generation(pid).ok()? == before).then_some(result)
}

pub(crate) fn open_files(pid: u32) -> Result<Vec<PathBuf>> {
    valid_pid(pid)?;
    let before = generation(pid)?;
    let mut result = platform::open_files(pid)?;
    ensure!(
        generation(pid)? == before,
        "Process changed while inspecting open files"
    );
    result.sort();
    result.dedup();
    Ok(result)
}

pub(crate) fn all_pids() -> Result<Vec<u32>> {
    let mut pids = platform::all_pids()?;
    ensure!(
        pids.len() <= MAX_PROCESSES,
        "Process inventory is too large"
    );
    pids.retain(|pid| *pid > 0 && *pid <= i32::MAX as u32);
    pids.sort_unstable();
    pids.dedup();
    Ok(pids)
}

pub(crate) fn children_map() -> Result<HashMap<u32, Vec<u32>>> {
    let mut result = HashMap::new();
    for pid in all_pids()? {
        // Processes may exit during an inventory; inaccessible system processes
        // are not evidence about one of the user's agent terminals.
        if let Ok(parent) = platform::parent(pid) {
            result.entry(parent).or_insert_with(Vec::new).push(pid);
        }
    }
    Ok(result)
}

pub(crate) fn is_alive(pid: u32) -> bool {
    if valid_pid(pid).is_err() {
        return false;
    }
    // EPERM means the process exists but belongs to another protection domain.
    (unsafe { libc::kill(pid as libc::pid_t, 0) == 0 })
        || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(any(target_os = "linux", test))]
struct LinuxStat {
    parent: u32,
    group: i32,
    foreground: i32,
    tty: bool,
    generation: u64,
    name: String,
}

#[cfg(any(target_os = "linux", test))]
fn parse_linux_stat(stat: &str) -> Result<LinuxStat> {
    let begin = stat.find('(').context("Missing process name")?;
    let end = stat.rfind(')').context("Missing process metadata")?;
    ensure!(end > begin, "Invalid process metadata");
    let fields: Vec<_> = stat[end + 1..].split_whitespace().collect();
    ensure!(fields.len() >= 20, "Incomplete process metadata");
    Ok(LinuxStat {
        parent: fields[1].parse()?,
        group: fields[2].parse()?,
        foreground: fields[5].parse()?,
        tty: fields[4].parse::<i64>()? != 0,
        generation: fields[19].parse()?,
        name: stat[begin + 1..end].to_owned(),
    })
}

#[cfg(any(target_os = "macos", test))]
fn parse_macos_argv(bytes: &[u8]) -> Result<Vec<String>> {
    ensure!(
        bytes.len() >= 4 && bytes.len() <= MAX_ARGUMENT_BYTES,
        "Invalid process argument buffer"
    );
    let count = i32::from_ne_bytes(bytes[..4].try_into()?);
    ensure!(
        count >= 0 && count as usize <= bytes.len(),
        "Invalid process argument count"
    );
    if count == 0 {
        return Ok(Vec::new());
    }
    let mut offset = 4;
    // KERN_PROCARGS2 contains argc, executable path, NUL padding, then exactly
    // argc NUL-terminated arguments. The remainder is the environment, never argv.
    offset += bytes[offset..]
        .iter()
        .position(|b| *b == 0)
        .context("Missing executable terminator")?
        + 1;
    while bytes.get(offset) == Some(&0) {
        offset += 1;
    }
    let mut result = Vec::with_capacity(count as usize);
    for _ in 0..count {
        ensure!(offset < bytes.len(), "Incomplete process arguments");
        let length = bytes[offset..]
            .iter()
            .position(|b| *b == 0)
            .context("Missing argument terminator")?;
        result.push(String::from_utf8_lossy(&bytes[offset..offset + length]).into_owned());
        offset += length + 1;
    }
    Ok(result)
}

#[cfg(target_os = "linux")]
mod platform {
    use super::*;
    use std::fs;

    fn stat(pid: u32) -> Result<LinuxStat> {
        parse_linux_stat(&fs::read_to_string(format!("/proc/{pid}/stat"))?)
    }
    pub(super) fn generation(pid: u32) -> Result<u64> {
        Ok(stat(pid)?.generation)
    }
    pub(super) fn parent(pid: u32) -> Result<u32> {
        Ok(stat(pid)?.parent)
    }
    pub(super) fn name(pid: u32) -> Result<String> {
        Ok(fs::read_to_string(format!("/proc/{pid}/comm"))?
            .trim()
            .to_owned())
    }
    pub(super) fn argv(pid: u32) -> Result<Vec<String>> {
        use std::io::Read;
        let mut bytes = Vec::new();
        fs::File::open(format!("/proc/{pid}/cmdline"))?
            .take((MAX_ARGUMENT_BYTES + 1) as u64)
            .read_to_end(&mut bytes)?;
        ensure!(
            bytes.len() <= MAX_ARGUMENT_BYTES,
            "Process arguments are too large"
        );
        if bytes.is_empty() {
            return Ok(Vec::new());
        }
        if bytes.last() == Some(&0) {
            bytes.pop();
        }
        Ok(bytes
            .split(|b| *b == 0)
            .map(|arg| String::from_utf8_lossy(arg).into_owned())
            .collect())
    }
    pub(super) fn cwd(pid: u32) -> Option<PathBuf> {
        fs::read_link(format!("/proc/{pid}/cwd")).ok()
    }
    pub(super) fn open_files(pid: u32) -> Result<Vec<PathBuf>> {
        let mut result = Vec::new();
        for (count, fd) in fs::read_dir(format!("/proc/{pid}/fd"))?
            .flatten()
            .enumerate()
        {
            ensure!(
                count < MAX_OPEN_FILES,
                "Too many open files to identify safely"
            );
            if let Ok(path) = fs::read_link(fd.path()) {
                result.push(path);
            }
        }
        Ok(result)
    }
    pub(super) fn all_pids() -> Result<Vec<u32>> {
        let mut pids = Vec::new();
        for entry in fs::read_dir("/proc")?.flatten() {
            if let Some(pid) = entry
                .file_name()
                .to_str()
                .and_then(|name| name.parse().ok())
            {
                pids.push(pid);
                ensure!(
                    pids.len() <= MAX_PROCESSES,
                    "Process inventory is too large"
                );
            }
        }
        Ok(pids)
    }
    fn started_at(ticks: u64) -> Option<i64> {
        let clock = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
        if clock <= 0 {
            return None;
        }
        let boot: u64 = fs::read_to_string("/proc/stat")
            .ok()?
            .lines()
            .find_map(|line| line.strip_prefix("btime "))?
            .trim()
            .parse()
            .ok()?;
        i64::try_from(u128::from(boot) * 1000 + u128::from(ticks) * 1000 / clock as u128).ok()
    }
    pub(super) fn snapshot(pid: u32) -> Result<ProcessInfo> {
        let stat = stat(pid)?;
        Ok(ProcessInfo {
            pid,
            parent_pid: stat.parent,
            process_group: stat.group,
            foreground_group: stat.foreground,
            has_tty: stat.tty,
            name: stat.name,
            argv: argv(pid)?,
            cwd: cwd(pid),
            generation: stat.generation,
            started_at_epoch_ms: started_at(stat.generation),
        })
    }
}

#[cfg(target_os = "macos")]
mod platform {
    use super::*;
    use anyhow::bail;
    use std::{ffi::OsString, mem, os::unix::ffi::OsStringExt};

    fn bsd(pid: u32) -> Result<libc::proc_bsdinfo> {
        let mut info: libc::proc_bsdinfo = unsafe { mem::zeroed() };
        let size = mem::size_of_val(&info);
        let read = unsafe {
            libc::proc_pidinfo(
                pid as i32,
                libc::PROC_PIDTBSDINFO,
                0,
                (&mut info as *mut libc::proc_bsdinfo).cast(),
                size as i32,
            )
        };
        ensure!(
            read == size as i32,
            "Process metadata unavailable: {}",
            std::io::Error::last_os_error()
        );
        ensure!(info.pbi_pid == pid, "Process identity changed");
        Ok(info)
    }
    fn birth(info: &libc::proc_bsdinfo) -> Result<u64> {
        ensure!(
            info.pbi_start_tvusec < 1_000_000,
            "Invalid process birth time"
        );
        info.pbi_start_tvsec
            .checked_mul(1_000_000)
            .and_then(|s| s.checked_add(info.pbi_start_tvusec))
            .context("Process birth time overflow")
    }
    fn string(chars: &[libc::c_char]) -> String {
        String::from_utf8_lossy(
            &chars
                .iter()
                .take_while(|c| **c != 0)
                .map(|c| *c as u8)
                .collect::<Vec<_>>(),
        )
        .into_owned()
    }
    fn process_name(info: &libc::proc_bsdinfo) -> String {
        let name = string(&info.pbi_name);
        if name.is_empty() {
            string(&info.pbi_comm)
        } else {
            name
        }
    }
    pub(super) fn generation(pid: u32) -> Result<u64> {
        birth(&bsd(pid)?)
    }
    pub(super) fn parent(pid: u32) -> Result<u32> {
        Ok(bsd(pid)?.pbi_ppid)
    }
    pub(super) fn name(pid: u32) -> Result<String> {
        Ok(process_name(&bsd(pid)?))
    }
    pub(super) fn argv(pid: u32) -> Result<Vec<String>> {
        let mut mib = [libc::CTL_KERN, libc::KERN_PROCARGS2, pid as i32];
        let mut size = 0usize;
        let status = unsafe {
            libc::sysctl(
                mib.as_mut_ptr(),
                mib.len() as u32,
                std::ptr::null_mut(),
                &mut size,
                std::ptr::null_mut(),
                0,
            )
        };
        ensure!(
            status == 0 && (4..=MAX_ARGUMENT_BYTES).contains(&size),
            "Process arguments unavailable"
        );
        let mut buffer = vec![0u8; size];
        let status = unsafe {
            libc::sysctl(
                mib.as_mut_ptr(),
                mib.len() as u32,
                buffer.as_mut_ptr().cast(),
                &mut size,
                std::ptr::null_mut(),
                0,
            )
        };
        ensure!(
            status == 0 && size <= buffer.len(),
            "Process arguments changed or became unavailable"
        );
        buffer.truncate(size);
        parse_macos_argv(&buffer)
    }
    fn path(value: &libc::vnode_info_path) -> Option<PathBuf> {
        let bytes: Vec<_> = value
            .vip_path
            .iter()
            .flatten()
            .take_while(|c| **c != 0)
            .map(|c| *c as u8)
            .collect();
        let path = PathBuf::from(OsString::from_vec(bytes));
        path.is_absolute().then_some(path)
    }
    pub(super) fn cwd(pid: u32) -> Option<PathBuf> {
        let mut info: libc::proc_vnodepathinfo = unsafe { mem::zeroed() };
        let size = mem::size_of_val(&info);
        let read = unsafe {
            libc::proc_pidinfo(
                pid as i32,
                libc::PROC_PIDVNODEPATHINFO,
                0,
                (&mut info as *mut libc::proc_vnodepathinfo).cast(),
                size as i32,
            )
        };
        if read != size as i32 {
            return None;
        }
        path(&info.pvi_cdir)
    }
    pub(super) fn all_pids() -> Result<Vec<u32>> {
        let needed = unsafe { libc::proc_listallpids(std::ptr::null_mut(), 0) };
        ensure!(needed > 0, "Process inventory unavailable");
        let mut capacity = needed as usize + 64;
        for _ in 0..3 {
            ensure!(capacity <= MAX_PROCESSES, "Process inventory is too large");
            let mut pids = vec![0i32; capacity];
            let count = unsafe {
                libc::proc_listallpids(
                    pids.as_mut_ptr().cast(),
                    (pids.len() * mem::size_of::<i32>()) as i32,
                )
            };
            ensure!(count >= 0, "Process inventory unavailable");
            if (count as usize) < pids.len() {
                pids.truncate(count as usize);
                return Ok(pids
                    .into_iter()
                    .filter(|pid| *pid > 0)
                    .map(|pid| pid as u32)
                    .collect());
            }
            capacity = capacity.saturating_mul(2);
        }
        bail!("Process inventory changed too quickly")
    }

    // Darwin sys/proc_info.h ABI, used because libc 0.2 exposes the nested
    // vnode_info_path but not these two wrapper structs/flavor constants.
    // https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/proc_info.h
    #[repr(C)]
    struct FileInfo {
        open_flags: u32,
        status: u32,
        offset: i64,
        kind: i32,
        guard_flags: u32,
    }
    #[repr(C)]
    struct VnodeFdPath {
        file: FileInfo,
        vnode: libc::vnode_info_path,
    }

    pub(super) fn open_files(pid: u32) -> Result<Vec<PathBuf>> {
        const VNODE_FD: u32 = 1;
        const VNODE_PATH_INFO: i32 = 2;
        let needed = unsafe {
            libc::proc_pidinfo(
                pid as i32,
                libc::PROC_PIDLISTFDS,
                0,
                std::ptr::null_mut(),
                0,
            )
        };
        ensure!(needed >= 0, "Open file inventory unavailable");
        let item_size = mem::size_of::<libc::proc_fdinfo>();
        let mut capacity = needed as usize / item_size + 32;
        let mut inventory = None;
        for _ in 0..3 {
            ensure!(
                capacity <= MAX_OPEN_FILES,
                "Too many open files to identify safely"
            );
            let mut fds: Vec<libc::proc_fdinfo> =
                (0..capacity).map(|_| unsafe { mem::zeroed() }).collect();
            let read = unsafe {
                libc::proc_pidinfo(
                    pid as i32,
                    libc::PROC_PIDLISTFDS,
                    0,
                    fds.as_mut_ptr().cast(),
                    (fds.len() * item_size) as i32,
                )
            };
            ensure!(
                read >= 0 && read as usize % item_size == 0,
                "Open file inventory unavailable"
            );
            if (read as usize) < fds.len() * item_size {
                fds.truncate(read as usize / item_size);
                inventory = Some(fds);
                break;
            }
            capacity = capacity.saturating_mul(2);
        }
        let mut result = Vec::new();
        for fd in inventory.context("Open file inventory changed too quickly")? {
            if fd.proc_fdtype != VNODE_FD {
                continue;
            }
            let mut info: VnodeFdPath = unsafe { mem::zeroed() };
            let size = mem::size_of_val(&info);
            let read = unsafe {
                libc::proc_pidfdinfo(
                    pid as i32,
                    fd.proc_fd,
                    VNODE_PATH_INFO,
                    (&mut info as *mut VnodeFdPath).cast(),
                    size as i32,
                )
            };
            // An FD may close between inventory and lookup. It is not evidence
            // for another conversation, and is never replaced with a CWD guess.
            if read == size as i32 {
                if let Some(path) = path(&info.vnode) {
                    result.push(path);
                }
            }
        }
        Ok(result)
    }
    pub(super) fn snapshot(pid: u32) -> Result<ProcessInfo> {
        let info = bsd(pid)?;
        let generation = birth(&info)?;
        let foreground = i32::try_from(info.e_tpgid).unwrap_or(-1);
        Ok(ProcessInfo {
            pid,
            parent_pid: info.pbi_ppid,
            process_group: i32::try_from(info.pbi_pgid).context("Invalid process group")?,
            foreground_group: foreground,
            has_tty: info.e_tdev != u32::MAX && foreground > 0,
            name: process_name(&info),
            argv: argv(pid)?,
            cwd: cwd(pid),
            generation,
            started_at_epoch_ms: i64::try_from(generation / 1000).ok(),
        })
    }
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
compile_error!("AgentShell process discovery currently supports Linux and macOS");

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs::File,
        process::{Child, Command, Stdio},
        time::{Duration, Instant},
    };
    struct OwnedChild(Child);
    impl Drop for OwnedChild {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    #[test]
    fn self_snapshot_has_stable_generation_arguments_and_cwd() {
        let pid = std::process::id();
        let info = snapshot(pid).unwrap();
        assert_eq!(info.generation, generation(pid).unwrap());
        assert_eq!(info.parent_pid, unsafe { libc::getppid() } as u32);
        assert!(!info.name.is_empty());
        assert!(!info.argv.is_empty());
        assert_eq!(info.argv, argv(pid).unwrap());
        assert_eq!(
            info.cwd.unwrap().canonicalize().unwrap(),
            std::env::current_dir().unwrap().canonicalize().unwrap()
        );
        let started = info.started_at_epoch_ms.unwrap();
        assert!(started <= chrono::Utc::now().timestamp_millis() + 1000);
        assert!(chrono::Utc::now().timestamp_millis() - started < 86_400_000);
    }
    #[test]
    fn own_open_file_preserves_spaces_and_unicode() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("agent log ação Ω.jsonl");
        let _file = File::create(&path).unwrap();
        assert!(open_files(std::process::id())
            .unwrap()
            .contains(&path.canonicalize().unwrap()));
    }
    #[test]
    fn owned_child_is_discovered_and_reaped_process_is_not_alive() {
        let mut child = OwnedChild(
            Command::new("sleep")
                .arg("10")
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap(),
        );
        let pid = child.0.id();
        assert!(is_alive(pid));
        let deadline = Instant::now() + Duration::from_secs(2);
        while !children_map()
            .unwrap()
            .get(&std::process::id())
            .is_some_and(|children| children.contains(&pid))
        {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(all_pids().unwrap().contains(&pid));
        assert_eq!(snapshot(pid).unwrap().parent_pid, std::process::id());
        child.0.kill().unwrap();
        child.0.wait().unwrap();
        assert!(!is_alive(pid));
        assert!(generation(pid).is_err());
    }
    #[test]
    fn invalid_pids_are_never_observed_or_signaled() {
        for pid in [0, u32::MAX] {
            assert!(snapshot(pid).is_err());
            assert!(generation(pid).is_err());
            assert!(open_files(pid).is_err());
            assert!(!is_alive(pid));
            assert!(name(pid).is_none());
        }
    }
    #[test]
    fn linux_stat_parser_handles_parentheses_and_retains_start_ticks() {
        let mut fields = vec!["0"; 20];
        fields[0] = "S";
        fields[1] = "234";
        fields[2] = "100";
        fields[4] = "1024";
        fields[5] = "100";
        fields[19] = "987654";
        let value =
            parse_linux_stat(&format!("123 (name (with) spaces) {}", fields.join(" "))).unwrap();
        assert_eq!(value.parent, 234);
        assert_eq!(value.group, 100);
        assert_eq!(value.foreground, 100);
        assert!(value.tty);
        assert_eq!(value.generation, 987654);
        assert_eq!(value.name, "name (with) spaces");
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_generation_is_byte_for_byte_compatible_with_existing_bindings() {
        let pid = std::process::id();
        let text = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
        let fields: Vec<_> = text[text.rfind(')').unwrap() + 2..]
            .split_whitespace()
            .collect();
        assert_eq!(generation(pid).unwrap(), fields[19].parse::<u64>().unwrap());
    }
    #[test]
    fn macos_argument_parser_preserves_empty_arguments_and_excludes_environment() {
        let mut data = 3i32.to_ne_bytes().to_vec();
        data.extend_from_slice(
            b"/opt/homebrew/bin/agent\0\0agent\0\0space argument\0SECRET=not-an-argument\0",
        );
        assert_eq!(
            parse_macos_argv(&data).unwrap(),
            ["agent", "", "space argument"]
        );
    }
    #[test]
    fn malformed_native_argument_and_stat_data_fail_closed() {
        assert!(parse_macos_argv(&[0, 0]).is_err());
        assert!(parse_macos_argv(&(-1i32).to_ne_bytes()).is_err());
        assert!(parse_linux_stat("123 malformed").is_err());
    }
}
