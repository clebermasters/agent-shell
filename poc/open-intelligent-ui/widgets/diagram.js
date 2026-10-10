const stages=[
 {name:'Collect',hint:'Inputs & provenance',description:'Gather the source material and record where it came from. Keep assumptions visible before any transformation.',criterion:'Every input has an owner, source and timestamp.'},
 {name:'Validate',hint:'Quality & confidence',description:'Check schema, completeness and invariants. Stop the pipeline when a requirement fails rather than publishing an uncertain result.',criterion:'Required checks pass; uncertain values are flagged.'},
 {name:'Render',hint:'Clarity & interaction',description:'Turn verified data into a responsive view. Keep labels readable and provide controls that work with touch and keyboard.',criterion:'The view works on a small screen and with assistive tools.'},
 {name:'Review',hint:'Evidence & release',description:'Compare the final experience with its requirements. Test real interactions and retain the evidence needed to approve a release.',criterion:'Behavior, layout and isolation are verified.'}
];
let step=0;
$('#stages').innerHTML=stages.map((s,i)=>`<button class="stage" data-stage="${i}" aria-pressed="${i===0}"><span class="number">${i+1}</span><span>${s.name}<small>${s.hint}</small></span><span class="stage-status" aria-hidden="true">↗</span></button>`).join('');
function renderStage(){
 const current=stages[step];$('#stepLabel').textContent=current.name;$('#stepDescription').textContent=current.description;$('#criterion').textContent=current.criterion;$('#stepCount').textContent=`Step ${step+1} of 4`;
 $$('#stages button').forEach((e,i)=>e.setAttribute('aria-pressed',i===step));
 stages.forEach((_,i)=>{$('#stage'+i+' circle').setAttribute('fill',i===step?'var(--accent-soft)':'var(--panel)');$('#stage'+i+' text').setAttribute('fill',i===step?'var(--accent)':'var(--muted)')});
 $('#previous').disabled=step===0;$('#step').disabled=step===3;
}
function selectStage(value){step=Math.min(3,Math.max(0,value));widgetState={step};renderStage();saveState()}
$('#stages').onclick=event=>{const e=event.target.closest('[data-stage]');if(e)selectStage(Number(e.dataset.stage))};
$('#step').onclick=()=>selectStage(step+1);$('#previous').onclick=()=>selectStage(step-1);
$('#ask').onclick=()=>sendPrompt(`Explain the ${stages[step].name} stage in this illustrative release pipeline. Exit criterion: ${stages[step].criterion} Suggest concrete checks for my project.`);
window.addEventListener('widget-restore',()=>{step=Number.isInteger(widgetState.step)?Math.min(3,Math.max(0,widgetState.step)):0;renderStage()});renderStage();
