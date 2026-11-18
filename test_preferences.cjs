const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/static/app.js','utf8')
  .replace(/^import[^\n]+/, 'const layoutWeek=()=>[],lunarLabel=()=>"";')
  .replace(/\nboot\(\);\n/, '\nawait boot();\n');

async function setup(){
  const elements=new Map(),storage=new Map();let pending=null,fail=false;
  let account={id:1,name:'테스트',admin:0,shift_team:'B',shift_on:1};
  class Element {
    constructor(){this.value='';this.checked=false;this.style={};this.classList={add(){}};this.children=[];}
    append(...items){this.children.push(...items);}replaceChildren(){this.children=[];}
    setAttribute(){}querySelectorAll(){return [];}showModal(){}close(){}
  }
  const document={getElementById(id){if(!elements.has(id))elements.set(id,new Element());return elements.get(id);},createElement(){return new Element();},addEventListener(){}};
  const fetch=async(path,opts)=>{
    let body,status=200;
    if(path==='/api/me'){
      body={user:{...account},csrf:'test'};
      if(pending){const gate=pending;pending=null;await gate;}
    }else if(path==='/api/preferences'){
      assert.equal(opts.method,'PUT');assert.equal(opts.headers['X-CSRF-Token'],'test');
      if(fail){status=500;body={error:'저장 실패'};}
      else {account={...account,...JSON.parse(opts.body)};body={user:{...account}};}
    }else if(path.startsWith('/api/events'))body=[];
    else if(path.startsWith('/api/lunar')||path.startsWith('/api/holidays'))body={};else throw Error(path);
    return {ok:status===200,status,json:async()=>body};
  };
  const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;
  const f=await new AsyncFunction('document','fetch','localStorage','navigator','setInterval',source+'\nreturn {load,boot,shift};')(
    document,fetch,{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v)},{},()=>{});
  return {f,e:id=>elements.get(id),storage,delayNextAccount(){let release;pending=new Promise(r=>release=r);return release;},failSave(){fail=true;}};
}
test('saved account setting overrides stale device preference and no-shift is always weekday day',async()=>{
  const {f,e,storage}=await setup();assert.equal(e('team').value,'B');
  e('team').value='none';await e('team').onchange();assert.equal(f.shift(new Date(2026,10,17)),'day');assert.equal(f.shift(new Date(2026,10,23)),'day');assert.equal(f.shift(new Date(2026,10,21)),'');
  await f.boot();assert.equal(e('team').value,'none');assert.equal(JSON.parse(storage.get('familyCalendar.shift')).team,'none');
  e('shiftOn').checked=false;await e('shiftOn').onchange();await f.boot();assert.equal(f.shift(new Date(2026,10,17)),'');
});
test('refresh started before preference save cannot reset it to an old value',async()=>{
  const {f,e,delayNextAccount}=await setup();const release=delayNextAccount();
  const refresh=f.load();e('team').value='none';await e('team').onchange();
  release();await refresh;assert.equal(e('team').value,'none');assert.equal(f.shift(new Date(2026,10,17)),'day');
});
test('failed account save restores prior selection and reports the failure',async()=>{
  const {e,failSave}=await setup();failSave();e('team').value='none';await e('team').onchange();assert.equal(e('team').value,'B');assert.equal(e('message').textContent,'저장 실패');assert.equal(e('shiftOn').disabled,false);
});
