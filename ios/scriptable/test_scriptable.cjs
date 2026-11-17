const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/FamicalCalendar.js','utf8').split('// SCRIPTABLE_ENTRY_POINT')[0];
async function create() {
 const storage=new Map();let expired=false;const password='synthetic-test-password-123';
 const Keychain={contains:k=>storage.has(k),get:k=>storage.get(k),set:(k,v)=>storage.set(k,v),remove:k=>storage.delete(k)};
 class Request {
  constructor(url){this.url=url;}
  async loadJSON(){
   assert.equal(this.allowInsecureRequest,false);assert.equal(this.onRedirect({url:'https://evil.test/'}),null);
   const path=new URL(this.url).pathname;
   if(path==='/api/me'){this.response={statusCode:200,cookies:[{name:'session',value:'anon-session'}]};return {csrf:'csrf-token'};}
   if(path==='/api/login'){
    assert.equal(this.headers.Cookie,'session=anon-session');assert.equal(this.headers['X-CSRF-Token'],'csrf-token');assert.equal(JSON.parse(this.body).password,password);
    this.response={statusCode:200,cookies:[{name:'session',value:'logged-session'}]};return {ok:true};
   }
   assert.equal(this.headers.Cookie,'session=logged-session');this.response={statusCode:expired?401:200,cookies:[]};
   return expired?{error:'로그인 필요'}:[{id:1,title:'공유 여행',day:'2026-11-16',end_day:'2026-11-18',clock:'',visibility:'shared'},{id:2,title:'개인 일정',day:'2026-11-17',end_day:'2026-11-17',clock:'',visibility:'private'}];
  }
 }
 class Alert {addTextField(){}addSecureTextField(){}addAction(){}addCancelAction(){}async presentAlert(){return 0;}textFieldValue(i){return ['https://calendar.example.com','family1',password][i];}}
 class Node {constructor(){this.children=[];}addStack(){const s=new Node();this.children.push(s);return s;}addText(s){const t={text:s,centerAlignText(){}};this.children.push(t);return t;}layoutVertically(){}addSpacer(){}setPadding(){}async presentLarge(){}}
 class Color {constructor(value){this.value=value;}};class Size{constructor(w,h){this.width=w;this.height=h;}}
 const Font={boldSystemFont:s=>s,systemFont:s=>s};
 const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;
 const f=await new AsyncFunction('Keychain','Request','Alert','Font','Color','ListWidget','Size',source+'\nreturn {normalizeServer,shiftFor,visibleToday,loginInteractive,readSnapshot,buildWidget,load};')(Keychain,Request,Alert,Font,Color,Node,Size);
 return {f,storage,password,expire:()=>expired=true};
}
test('only HTTPS origins are accepted',async()=>{
 const {f}=await create();assert.equal(f.normalizeServer(' calendar.example.com '),'https://calendar.example.com/');
 for(const s of ['http://calendar.example.com','https://user:pw@calendar.example.com','https://calendar.example.com/path','https://calendar.example.com/?token=x','file:///private','https://calendar.example.com:0'])assert.throws(()=>f.normalizeServer(s));
});
test('calendar shifts and private event filtering',async()=>{
 const {f}=await create();const cfg={team:'A',shiftOn:true,includePrivate:false};
 assert.equal(f.shiftFor(new Date(2026,10,17),cfg),'A조 야간');assert.equal(f.shiftFor(new Date(2026,10,23),cfg),'A조 주간');assert.equal(f.shiftFor(new Date(2026,10,21),cfg),'');
 const events=[{day:'2026-11-16',end_day:'2026-11-18',visibility:'shared'},{day:'2026-11-17',visibility:'private'}];
 assert.equal(f.visibleToday(events,new Date(2026,10,17),cfg).length,1);assert.equal(f.visibleToday(events,new Date(2026,10,17),{...cfg,includePrivate:true}).length,2);
});
test('CSRF login, session storage and expiry clearing',async()=>{
 const {f,storage,password,expire}=await create();assert.equal(await f.loginInteractive(),true);
 assert(![...storage.values()].some(v=>v.includes(password)));
 const cfg=f.load('config');const snapshot=await f.readSnapshot(cfg,new Date(2026,10,17));assert.equal(snapshot.events.length,1);assert.equal(snapshot.events[0].title,'공유 여행');
 for(const family of ['small','medium','large']){const w=f.buildWidget(cfg,snapshot,new Date(2026,10,17),family);assert.equal(w.url,cfg.server);assert(w.children.length>0);}
 expire();assert.equal((await f.readSnapshot(cfg,new Date(2026,10,17))).events.length,0);assert.equal(f.load('cookie'),null);assert.equal(f.load('cache'),null);
});
