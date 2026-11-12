const $=id=>document.getElementById(id);const status=$('status');
function base(){return `/api/v1/projects/${encodeURIComponent($('projectId').value)}`}
function say(v){status.textContent=v}
function render(data){window.dispatchEvent(new CustomEvent('sddp:topology',{detail:data}))}
async function load(){try{say('Loading…');const [r,c]=await Promise.all([fetch(`${base()}/topology?environment=${$('environment').value}`),fetch(`${base()}/contracts`)]);if(!r.ok)throw Error(await r.text());if(!c.ok)throw Error(await c.text());render(await r.json());const contracts=await c.json();$('contractId').replaceChildren(...contracts.map(item=>{const option=document.createElement('option');option.value=item.id;option.textContent=`${item.name} (${item.version})`;return option}));say('Topology loaded')}catch(e){say(e.message)}}
async function buildIr(){try{say('Building IR…');const r=await fetch(`${base()}/ir?environment=${$('environment').value}`,{method:'POST'});if(!r.ok)throw Error(await r.text());$('details').textContent=JSON.stringify(await r.json(),null,2);say('IR generated')}catch(e){say(e.message)}}
$('load').onclick=load;$('ir').onclick=buildIr;
async function post(url,data){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});if(!r.ok)throw Error(await r.text());return r.json()}
$('addService').onclick=async()=>{try{await post(`${base()}/services`,{name:$('serviceName').value,external:$('serviceType').value==='true'});await load()}catch(e){say(e.message)}};
$('createProject').onclick=async()=>{try{const project=await post('/api/v1/projects',{name:$('projectName').value});$('projectId').value=project.id;await load()}catch(e){say(e.message)}};
$('addContract').onclick=async()=>{try{await post(`${base()}/contracts`,{name:$('contractName').value,version:$('contractVersion').value,requestSchema:JSON.parse($('requestSchema').value),responseSchema:JSON.parse($('responseSchema').value)});await load()}catch(e){say(e.message)}};
function mappingRuleRow(){
 const row=document.createElement('div');row.className='mapping-row';
 const type=document.createElement('select');type.setAttribute('aria-label','Тип правила');
 for(const value of ['DIRECT_COPY','CONCATENATE','HARDCODED_VALUE']){const option=document.createElement('option');option.value=value;option.textContent=value;type.append(option)}
 const from=document.createElement('input');from.placeholder='Источник: $.field';from.setAttribute('aria-label','Источник или значение JSON');
 const to=document.createElement('input');to.placeholder='Назначение: $.field';to.setAttribute('aria-label','Назначение');
 const separator=document.createElement('input');separator.placeholder='Разделитель';separator.value=' ';separator.setAttribute('aria-label','Разделитель');separator.hidden=true;
 const remove=document.createElement('button');remove.type='button';remove.textContent='Удалить';remove.onclick=()=>row.remove();
 type.onchange=()=>{separator.hidden=type.value!=='CONCATENATE';from.placeholder=type.value==='CONCATENATE'?'Пути через запятую: $.first, $.last':type.value==='HARDCODED_VALUE'?'Значение JSON, например "RUB"':'Источник: $.field'};
 row.append(type,from,to,separator,remove);$('mappingRules').append(row);
}
function createMapping(){
 const rows=[...$('mappingRules').children];if(!rows.length)throw Error('Добавьте хотя бы одно правило');
 const source=$('mappingSource').value.trim(),target=$('mappingTarget').value.trim();if(!source||!target)throw Error('Укажите оба контракта маппинга');
 const transformations=rows.map(row=>{const [type,from,to,separator]=row.children;const rule={type:type.value,to:to.value.trim()};if(!rule.to.startsWith('$.'))throw Error('Назначение должно начинаться с $.');
 if(type.value==='DIRECT_COPY'){rule.from=from.value.trim();if(!rule.from.startsWith('$.'))throw Error('Источник должен начинаться с $.')}
 if(type.value==='CONCATENATE'){rule.from=from.value.split(',').map(v=>v.trim());if(rule.from.some(v=>!v.startsWith('$.')))throw Error('Каждый источник должен начинаться с $.');rule.separator=separator.value}
 if(type.value==='HARDCODED_VALUE')rule.value=JSON.parse(from.value);return rule});
 return {source_contract:source,target_contract:target,transformations};
}
$('addMappingRule').onclick=mappingRuleRow;
$('previewMapping').onclick=()=>{try{$('mapping').value=JSON.stringify(createMapping(),null,2);say('Манифест маппинга сформирован')}catch(e){say(e.message)}};
$('addConnection').onclick=async()=>{try{const raw=$('mapping').value.trim();const mapping=$('mappingRules').children.length?createMapping():(raw?JSON.parse(raw):null);if(mapping)$('mapping').value=JSON.stringify(mapping,null,2);await post(`${base()}/connections`,{environment:$('environment').value,sourceServiceId:$('source').value,targetServiceId:$('target').value,contractId:$('contractId').value||null,transportType:$('transport').value,connectionSettings:{host:$('host').value,port:Number($('port').value),target_endpoint:$('endpoint').value},mappingRules:mapping});await load()}catch(e){say(e.message)}};
$('download').onclick=async()=>{try{const r=await fetch(`${base()}/snapshot`,{method:'POST'});if(!r.ok)throw Error(await r.text());const link=document.createElement('a');link.href=URL.createObjectURL(await r.blob());link.download='topology-snapshot.json';link.click();URL.revokeObjectURL(link.href)}catch(e){say(e.message)}};
$('importFile').onchange=async e=>{try{const project=await post('/api/v1/projects/import',JSON.parse(await e.target.files[0].text()));$('projectId').value=project.id;await load()}catch(err){say(err.message)}};
const renderGraph=render;render=data=>{renderGraph(data);for(const id of ['source','target']){$(id).replaceChildren(...data.nodes.map(n=>{const option=document.createElement('option');option.value=n.id;option.textContent=n.name;return option}))}};
window.addEventListener('sddp:connection-selected',event=>{$('source').value=event.detail.source;$('target').value=event.detail.target;say('Выберите контракт и параметры связи, затем нажмите Add connection')});
