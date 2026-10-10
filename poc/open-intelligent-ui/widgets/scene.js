let renderer,scene,camera,product;
let zoom=1,active=true;
const sceneHost=$('#scene');
try {
 scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(40,1,.1,100);
 renderer=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});
 renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.shadowMap.enabled=true;
 renderer.shadowMap.type=THREE.PCFSoftShadowMap;sceneHost.prepend(renderer.domElement);
 scene.add(new THREE.HemisphereLight(0xc5caff,0x555b70,2.3));
 const light=new THREE.DirectionalLight(0xffffff,4);light.position.set(3,6,4);light.castShadow=true;light.shadow.mapSize.set(512,512);scene.add(light);
 product=new THREE.Group();const geometry=new THREE.BoxGeometry(1.5,1.5,1.5);
 const shell=new THREE.Mesh(geometry,new THREE.MeshStandardMaterial({color:0x8b86eb,metalness:.25,roughness:.35}));shell.position.y=.95;shell.castShadow=true;product.add(shell);
 const edges=new THREE.LineSegments(new THREE.EdgesGeometry(geometry),new THREE.LineBasicMaterial({color:0xc7c5ff}));edges.position.copy(shell.position);product.add(edges);
 const panel=new THREE.Mesh(new THREE.BoxGeometry(.82,.16,.015),new THREE.MeshStandardMaterial({color:0x1c2540,roughness:.4}));panel.position.set(0,1.18,.761);product.add(panel);
 for(let i=0;i<3;i++){const led=new THREE.Mesh(new THREE.SphereGeometry(.036,10,10),new THREE.MeshStandardMaterial({color:0x77eed0,emissive:0x1d6d57}));led.position.set(-.22+i*.22,.7,.78);product.add(led)}
 scene.add(product);
 const floor=new THREE.Mesh(new THREE.PlaneGeometry(100,100),new THREE.ShadowMaterial({opacity:.12}));floor.rotation.x=-Math.PI/2;floor.receiveShadow=true;scene.add(floor);
 const grid=new THREE.GridHelper(9,18,0xaaaac0,0xd5d6df);grid.position.y=.01;grid.material.transparent=true;grid.material.opacity=.25;scene.add(grid);
 renderer.domElement.addEventListener('webglcontextlost',event=>{event.preventDefault();$('#sceneError').hidden=false;window.__poc3dReady=false});
 renderer.domElement.addEventListener('webglcontextrestored',()=>{$('#sceneError').hidden=true;window.__poc3dReady=true;drawScene()});
 window.__poc3dReady=true;
} catch(error) {
 $('#sceneError').hidden=false;
 sceneHost.insertAdjacentHTML('afterbegin','<svg viewBox="0 0 320 240" role="img" aria-label="Static product preview"><path d="M160 35L245 80V175L160 220L75 175V80Z" fill="#8b86eb"/><path d="M75 80L160 125L245 80M160 125V220" stroke="#c7c5ff" fill="none" stroke-width="3"/></svg>');
}
function drawScene(){
 const yaw=Number($('#yaw').value),pitch=Number($('#pitch').value);
 $('#angle').textContent=yaw;$('#elevation').textContent=pitch;
 $('#zoomIn').disabled=zoom>=1.6;$('#zoomOut').disabled=zoom<=.7;
 ['iso','front','top'].forEach(id=>$('#'+id).setAttribute('aria-pressed',id==='iso'?yaw===0&&pitch===28:id==='front'?yaw===0&&pitch===0:yaw===0&&pitch===85));
 if(!renderer||!active)return;
 const bounds=sceneHost.getBoundingClientRect();if(!bounds.width||!bounds.height)return;
 renderer.setSize(bounds.width,bounds.height,false);camera.aspect=bounds.width/bounds.height;camera.updateProjectionMatrix();
 const angle=pitch*Math.PI/180,dist=5/zoom;
 camera.position.set(dist*Math.cos(angle)*.58, .8+dist*Math.sin(angle),dist*Math.cos(angle)*.82);camera.lookAt(0,.8,0);
 product.rotation.y=yaw*Math.PI/180;
 scene.background=new THREE.Color(getComputedStyle(document.documentElement).getPropertyValue('--panel').trim());
 renderer.render(scene,camera);
}
function saveScene(){widgetState={zoom};saveState()}
$('#yaw').oninput=drawScene;$('#pitch').oninput=drawScene;
function preset(pitch){$('#yaw').value=0;$('#pitch').value=pitch;drawScene();saveScene()}
$('#iso').onclick=()=>preset(28);$('#front').onclick=()=>preset(0);$('#top').onclick=()=>preset(85);
$('#reset').onclick=()=>{zoom=1;preset(28)};
$('#zoomIn').onclick=()=>{zoom=Math.min(1.6,zoom+.15);drawScene();saveScene()};$('#zoomOut').onclick=()=>{zoom=Math.max(.7,zoom-.15);drawScene();saveScene()};
let drag;
sceneHost.addEventListener('pointerdown',event=>{drag={x:event.clientX,yaw:Number($('#yaw').value)};sceneHost.setPointerCapture(event.pointerId)});
sceneHost.addEventListener('pointermove',event=>{if(!drag)return;$('#yaw').value=Math.round(Math.max(-180,Math.min(180,drag.yaw+(event.clientX-drag.x)*.7)));drawScene();saveScene()});
sceneHost.addEventListener('pointerup',()=>drag=null);sceneHost.addEventListener('pointercancel',()=>drag=null);
new ResizeObserver(drawScene).observe(sceneHost);
window.addEventListener('widget-theme',drawScene);
window.addEventListener('widget-visibility',event=>{active=event.detail;drawScene()});
window.addEventListener('widget-restore',()=>{zoom=typeof widgetState.zoom==='number'?Math.max(.7,Math.min(1.6,widgetState.zoom)):1;drawScene()});
$('#ask').onclick=()=>sendPrompt(`Discuss the sample product at rotation ${$('#yaw').value} degrees and elevation ${$('#pitch').value} degrees, zoom ${zoom.toFixed(2)}. Suggest useful details to inspect.`);
drawScene();
