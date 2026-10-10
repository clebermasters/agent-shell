function fieldValue(id,min,max,integer=false) {
  const input=$('#'+id), value=Number(input.value);
  const valid=input.value.trim()!=='' && Number.isFinite(value) && value>=min && value<=max && (!integer || Number.isInteger(value));
  input.setAttribute('aria-invalid',!valid);
  $('#'+id+'Error').textContent=valid?'':id==='people'?'Choose a whole number from 1 to 24.':id==='tip'?'Enter a tip from 0% to 100%.':'Enter a bill from $0 to $1,000,000.';
  return valid?value:null;
}
function calc() {
  const bill=fieldValue('bill',0,1000000),tip=fieldValue('tip',0,100),people=fieldValue('people',1,24,true);
  const valid=bill!==null&&tip!==null&&people!==null;
  $('#total').textContent=valid?(bill*(1+tip/100)/people).toFixed(2):'—';
  $('#grandTotal').textContent=valid?money(bill*(1+tip/100)):'—';
  $('#peopleLabel').textContent=people!==null?people+(people===1?' person':' people'):'Check group size';
  $('#tipAmount').textContent=bill!==null&&tip!==null?money(bill*tip/100)+' tip':'';
  $('#ask').disabled=!valid; $('#less').disabled=people!==null&&people<=1;$('#more').disabled=people!==null&&people>=24;
  $$('#tips button').forEach(e=>e.setAttribute('aria-pressed',tip!==null&&Number(e.dataset.tip)===tip));
}
$$('input').forEach(e=>e.oninput=calc);
$('#tips').onclick=event=>{const e=event.target.closest('[data-tip]');if(e){$('#tip').value=e.dataset.tip;calc();saveState()}};
function peopleChange(delta){$('#people').value=Math.min(24,Math.max(1,(Number($('#people').value)||1)+delta));calc();saveState();}
$('#less').onclick=()=>peopleChange(-1);$('#more').onclick=()=>peopleChange(1);
$('#ask').onclick=()=>sendPrompt(`Help split an illustrative USD bill of ${money(Number($('#bill').value))}, with ${$('#tip').value}% tip, between ${$('#people').value} people. Each pays $${$('#total').textContent}; total ${$('#grandTotal').textContent}. Explain any rounding difference.`);
window.addEventListener('widget-restore',calc);calc();
