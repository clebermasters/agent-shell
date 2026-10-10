const values = [6200,7400,7100,8900,9700,12100];
const months = ['Jan','Feb','Mar','Apr','May','Jun'];
let selected = 5;
let table = false;
$('#months').innerHTML = months.map((name,i) => `<button data-month="${i}" aria-label="Select ${name}" aria-pressed="${i===selected}">${name}</button>`).join('');
function chartValues() { const scale=Number($('#scale').value); return values.map((value,i) => value*(1+(scale-1)*i*.08)); }
function drawChart() {
  const data=chartValues(), max=Math.max(...data)*1.12;
  const points=data.map((value,i) => [12+i*63,145-value/max*125]);
  const path=points.map(([x,y],i) => `${i?'L':'M'}${x} ${y}`).join(' ');
  $('#series').setAttribute('d',path); $('#area').setAttribute('d',path+' L327 155 L12 155Z');
  $('#points').innerHTML=points.map(([x,y],i) => `<circle cx="${x}" cy="${y}" r="${i===selected?6:3}" fill="var(--accent)" stroke="var(--bg)" stroke-width="2"/>`).join('');
  $('#revenue').textContent=money(data.reduce((a,b)=>a+b,0)).replace('.00','');
  $('#growth').textContent='+'+Math.round((data[5]/data[0]-1)*100)+'% Jan → Jun';
  $('#chartValue').textContent=$('#scale').value;
  $('#scenarioNote').textContent=Number($('#scale').value)===1 ? 'Baseline · recorded sample values' : `Scenario ${$('#scale').value} · adds ${Math.round((Number($('#scale').value)-1)*8)}% growth per month`;
  $('#selectedMonth').textContent=months[selected]+' · selected month'; $('#selectedValue').textContent=money(data[selected]);
  $$('#months button').forEach((e,i)=>e.setAttribute('aria-pressed',i===selected));
  $('#rows').innerHTML=data.map((value,i)=>`<tr><td>${months[i]}</td><td>${money(value)}</td><td>${i?((value/data[i-1]-1)*100).toFixed(1)+'%':'—'}</td></tr>`).join('');
  $('#chartView').hidden=table; $('#tableView').hidden=!table;
  $('#showChart').setAttribute('aria-pressed',!table); $('#showTable').setAttribute('aria-pressed',table);
}
$('#scale').oninput=drawChart;
$('#months').onclick=event=>{ const button=event.target.closest('[data-month]'); if(!button)return; selected=Number(button.dataset.month); updateChartState(); };
function updateChartState(){ window.widgetState={selected,table};drawChart();saveState(); }
$('#showChart').onclick=()=>{table=false;updateChartState()}; $('#showTable').onclick=()=>{table=true;updateChartState()};
window.addEventListener('widget-restore',()=>{ selected=Number.isInteger(widgetState.selected)?Math.max(0,Math.min(5,widgetState.selected)):5;table=widgetState.table===true;drawChart(); });
$('#ask').onclick=()=>sendPrompt(`Analyze the illustrative revenue trend at growth scenario ${$('#scale').value}. Selected ${months[selected]} revenue: ${money(chartValues()[selected])}. Explain the assumptions and risks.`);
drawChart();
