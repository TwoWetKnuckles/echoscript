import {hashFile,makeManifest,checkManifest} from './core.mjs';
const $=id=>document.getElementById(id);
const pages=[...document.querySelectorAll('.page')];
function route(){const id=location.hash.slice(1)||'overview';const target=pages.find(p=>p.id===id)||pages[0];pages.forEach(p=>p.hidden=p!==target);document.querySelectorAll('nav a').forEach(a=>a.setAttribute('aria-current',a.hash==='#'+target.id?'page':'false'));$('breadcrumb').textContent='Workspace / '+({overview:'Overview',intake:'Local intake',verify:'Verify integrity',catalog:'Reference library',protocol:'Evidence protocol',status:'Build status'}[target.id]);document.title=`EchoScript · ${$('breadcrumb').textContent.split(' / ')[1]}`;}
document.querySelector('.skip').addEventListener('click',e=>{e.preventDefault();$('main').focus();});
addEventListener('hashchange',route);route();
for(let i=0;i<110;i++){const b=document.createElement('i');b.style.height=(8+Math.abs(Math.sin(i*1.7)*Math.cos(i*.13))*78)+'px';$('signal-bars').append(b);}
let manifest=null,objectUrl=null,generation=0;
function release(){generation++;manifest=null;$('source-result').hidden=true;const player=$('player');player.pause();player.removeAttribute('src');player.load();if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl=null;$('source').value='';$('intake-message').textContent='File released. No file is saved by this app.';}
$('clear').onclick=release;
$('source').onchange=async()=>{const file=$('source').files[0];if(!file){release();return;}const current=++generation;manifest=null;$('source-result').hidden=true;if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl=null;$('player').pause();$('player').removeAttribute('src');$('player').load();$('intake-message').className='';$('intake-message').textContent='Calculating SHA-256 locally…';try{const hash=await hashFile(file);if(current!==generation)return;manifest=makeManifest(file,hash);const fields={'Filename':file.name,'Size':`${file.size.toLocaleString()} bytes`,'SHA-256':hash,'Recorded (UTC)':manifest.recordedAtUtc};$('metadata').replaceChildren();for(const [key,value]of Object.entries(fields)){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=key;dd.textContent=value;$('metadata').append(dt,dd);}objectUrl=URL.createObjectURL(file);$('player').src=objectUrl;$('source-result').hidden=false;$('intake-message').textContent='Hash calculated. Source bytes were read without modification.';$('intake-message').className='success';}catch(e){if(current!==generation)return;$('intake-message').textContent=e.message;$('intake-message').className='error';}};
$('export').onclick=()=>{if(!manifest)return;const url=URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)+'\n'],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='echoscript-intake-'+manifest.artifact.sha256.slice(0,12)+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);};
let verificationGeneration=0;
function invalidateVerification(){verificationGeneration++;$('verification').textContent='Selection changed. Verify again to check these files.';$('verification').className='';$('verify-button').disabled=false;}
$('verify-source').onchange=invalidateVerification;
$('verify-manifest').onchange=invalidateVerification;
$('verify-form').onsubmit=async e=>{
  e.preventDefault();
  const current=++verificationGeneration;
  const file=$('verify-source').files[0],record=$('verify-manifest').files[0];
  $('verification').className='';$('verification').textContent='Verifying locally…';$('verify-button').disabled=true;
  try{
    if(!file||!record)throw Error('Select both files.');
    if(record.size>1024*1024)throw Error('Manifest exceeds 1 MiB.');
    let data;try{data=JSON.parse(await record.text());}catch{throw Error('The manifest must contain valid JSON.');}
    const result=checkManifest(data,file,await hashFile(file));
    if(current!==verificationGeneration)return;
    $('verification').textContent=result.match?`MATCH — SHA-256 and byte count agree.${result.filenameMatches?'':' Filename differs; file content matches.'}`:'MISMATCH — file bytes do not match this manifest.';
    $('verification').className=result.match?'success':'error';
  }catch(err){if(current!==verificationGeneration)return;$('verification').textContent=err.message;$('verification').className='error';}
  finally{if(current===verificationGeneration)$('verify-button').disabled=false;}
};
let catalog=[];
function renderCatalog(){const chosen=$('filter').value;$('catalog-cards').replaceChildren();for(const item of catalog.filter(x=>chosen==='all'||x.kind===chosen)){const card=document.createElement('article');for(const [tag,value,cls]of [['span',item.kind+' · revision 1','tag'],['h2',item.title,''],['p',item.summary,''],['pre',JSON.stringify(item.parameters,null,2),''],['p',item.warning,'muted']]){const el=document.createElement(tag);el.textContent=value;el.className=cls;card.append(el);}$('catalog-cards').append(card);}}
$('filter').onchange=renderCatalog;
fetch(new URL('./catalog.json', import.meta.url)).then(r=>{if(!r.ok)throw Error('Reference catalog could not be loaded.');return r.json();}).then(data=>{catalog=data;renderCatalog();}).catch(e=>{$('catalog-cards').textContent=e.message;});
// Retain object URLs while the page is in the back/forward cache.
// URLs are revoked on replacement or explicit release; document teardown releases them.

