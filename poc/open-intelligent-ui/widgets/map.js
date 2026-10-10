const stops=[
 {name:'San Francisco',point:[37.77,-122.42],detail:'Morning · waterfront & coffee',time:'9:00 AM'},
 {name:'Half Moon Bay',point:[37.46,-122.43],detail:'Midday · coast & lunch',time:'12:00 PM'},
 {name:'San Jose',point:[37.33,-121.89],detail:'Afternoon · museums & dinner',time:'3:00 PM'}
];
const map=L.map('map',{scrollWheelZoom:false,attributionControl:true,minZoom:7,maxZoom:16}).setView([37.55,-122.15],9);
map.attributionControl.setPrefix(false);map.attributionControl.addAttribution('AgentShell · schematic');
// Authored schematic basemap, bundled in this document. No remote tiles or APIs.
const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('xmlns','http://www.w3.org/2000/svg');svg.setAttribute('viewBox','0 0 600 600');
svg.innerHTML='<rect width="600" height="600" fill="#cbdfe9"/><path d="M200 0L205 115L175 220L235 365L330 460L355 600H600V0Z" fill="#e9ece2"/><path d="M290 0L260 150L340 310L310 400L405 475L490 470L410 340L430 180L405 0Z" fill="#bfd7e4"/><path d="M490 0L480 110L525 270L500 385L565 600H600V0Z" fill="#d9e2ce"/><g fill="none" stroke="#fff" stroke-width="5"><path d="M210 100Q190 260 270 395T470 520"/><path d="M270 395L365 380L410 340M330 460L480 470"/></g><g fill="none" stroke="#c5cdbb" stroke-width="1"><path d="M180 20L240 100L220 220M450 20L520 170L505 330M375 500L540 530"/></g><g font-family="system-ui,sans-serif" font-size="15" fill="#4f687e"><text x="35" y="340" transform="rotate(-75 35 340)">PACIFIC OCEAN</text><text x="290" y="225" transform="rotate(57 290 225)">SAN FRANCISCO BAY</text></g>';
const schematic=L.svgOverlay(svg,[[37.1,-122.8],[38.0,-121.6]],{interactive:false}).addTo(map);
const route=L.polyline(stops.map(s=>s.point),{color:'#6963d9',weight:3,dashArray:'6 8'}).addTo(map);
const markers=stops.map((s,i)=>L.circleMarker(s.point,{radius:8,color:'#fff',weight:3,fillColor:'#6963d9',fillOpacity:1}).addTo(map)
 .bindTooltip(s.name,{direction:'right',permanent:true,className:'stop-label',offset:[10,0]}).on('click',()=>selectStop(i)));
let selectedStop=0;
let viewChosen=false;
let live=false,basemap=null;
function distance(a,b){const r=Math.PI/180,lat=(b[0]-a[0])*r,lon=(b[1]-a[1])*r,h=Math.sin(lat/2)**2+Math.cos(a[0]*r)*Math.cos(b[0]*r)*Math.sin(lon/2)**2;return 6371*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h))}
$('#stops').innerHTML=stops.map((s,i)=>`<button class="stop" id="${i===2?'selectStop':'stop'+i}" data-stop="${i}" aria-pressed="${i===0}"><span class="stop-dot">${i+1}</span><span>${s.name}<small>${s.time}</small></span><span class="distance">${i?Math.round(distance(stops[i-1].point,s.point))+' km':'Start'}</span></button>`).join('');
function renderStop(pan=true){$('#stopName').textContent=stops[selectedStop].name;$('#stopDetail').textContent=stops[selectedStop].detail;$$('[data-stop]').forEach((e,i)=>e.setAttribute('aria-pressed',i===selectedStop));markers.forEach((marker,i)=>marker.setRadius(i===selectedStop?12:8));if(pan)map.panTo(stops[selectedStop].point,{animate:!matchMedia('(prefers-reduced-motion:reduce)').matches})}
function captureMap(){const center=map.getCenter();widgetState={selectedStop,center:[center.lat,center.lng],zoom:map.getZoom(),live};saveState()}
function setBasemap(enabled){
 if(basemap){map.removeLayer(basemap);basemap=null}
 live=enabled;$('#offlineMap').setAttribute('aria-pressed',!live);$('#liveMap').setAttribute('aria-pressed',live);
 if(live)map.removeLayer(schematic);else if(!map.hasLayer(schematic))schematic.addTo(map).bringToBack();
 $('#mapStatus').textContent=live?'Loading USGS basemap…':'Offline schematic · no connection needed.';
 if(live){
  basemap=L.tileLayer('https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}',{attribution:'USGS / The National Map',maxZoom:16}).addTo(map);
  basemap.on('load',()=>{if(live)$('#mapStatus').textContent='USGS basemap · online'});
  basemap.once('tileerror',()=>{if(live){setBasemap(false);$('#mapStatus').textContent='Basemap unavailable. Offline map is ready.';captureMap()}});
 }
 captureMap();
}
$('#offlineMap').onclick=()=>setBasemap(false);$('#liveMap').onclick=()=>setBasemap(true);
function selectStop(i){selectedStop=i;viewChosen=true;renderStop();captureMap()}
$('#stops').onclick=event=>{const e=event.target.closest('[data-stop]');if(e)selectStop(Number(e.dataset.stop))};
function fit(){map.fitBounds(route.getBounds(),{padding:[28,28],animate:false})}
$('#fitRoute').onclick=fit;
map.on('moveend zoomend',()=>{if(!restoring){viewChosen=true;captureMap()}});
new ResizeObserver(()=>{map.invalidateSize({pan:false});if(!viewChosen)fit()}).observe($('#map'));
window.addEventListener('widget-restore',()=>{selectedStop=Number.isInteger(widgetState.selectedStop)?Math.max(0,Math.min(2,widgetState.selectedStop)):0;renderStop(false);
 if(Array.isArray(widgetState.center)&&widgetState.center.length===2&&widgetState.center.every(Number.isFinite)&&Number.isFinite(widgetState.zoom)){
  viewChosen=true;map.setView(widgetState.center,Math.max(7,Math.min(16,widgetState.zoom)),{animate:false});
 }
 setBasemap(widgetState.live===true);
});
$('#ask').onclick=()=>sendPrompt(`Help plan the ${stops[selectedStop].name} stop in an illustrative Bay Area itinerary. ${stops[selectedStop].detail}, around ${stops[selectedStop].time}. Check real opening hours and travel times before recommending a schedule.`);
renderStop(false);fit();window.__pocMapReady=true;
