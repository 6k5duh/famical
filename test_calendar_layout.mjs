import test from 'node:test';
import assert from 'node:assert/strict';
import {layoutWeek,lunarLabel} from './static/calendar-layout.mjs';
const dates=['2026-11-22','2026-11-23','2026-11-24','2026-11-25','2026-11-26','2026-11-27','2026-11-28'];
const event=(id,day,end_day)=>({id,day,end_day});
test('lunar label is small inline text including leap months',()=>{
 assert.equal(lunarLabel({month:9,day:23,leap:false}),'(음 9.23)');
 assert.equal(lunarLabel({month:6,day:1,leap:true}),'(윤 6.1)');assert.equal(lunarLabel(null),'');
});
test('overlaps use separate lanes and non-overlaps reuse lanes',()=>{
 const rows=layoutWeek([event(1,'2026-11-23','2026-11-25'),event(2,'2026-11-24','2026-11-26'),event(3,'2026-11-26','2026-11-27')],dates);
 assert.deepEqual(rows.map(r=>[r.startColumn,r.endColumn,r.lane]),[[1,3,0],[2,4,1],[4,5,0]]);
});
test('bars continue across weeks and empty month cells are excluded',()=>{
 const e=event(1,'2026-11-20','2026-12-03');
 const middle=layoutWeek([e],dates)[0];assert.equal(middle.continuesBefore,true);assert.equal(middle.continuesAfter,true);assert.equal(middle.startColumn,0);assert.equal(middle.endColumn,6);
 const end=layoutWeek([e],[null,null,'2026-12-01','2026-12-02','2026-12-03','2026-12-04','2026-12-05'])[0];
 assert.equal(end.startColumn,2);assert.equal(end.endColumn,4);assert.equal(end.continuesAfter,false);
 assert.equal(layoutWeek([event(2,'2027-01-01','2027-01-02')],dates).length,0);
});

test('company bars show inclusive progress from the true start even in a new year or week',()=>{
 const work={...event(1,'2026-12-26','2027-01-03'),category:'work'};
 const family={...work,id:2,category:'family'};
 const days=[null,null,null,null,null,'2027-01-01','2027-01-02'];
 const rows=layoutWeek([work,family],days);
 const parts=rows.filter(r=>r.event===work);
 assert.deepEqual(parts.map(r=>[r.startColumn,r.endColumn,r.progress]),[[5,5,'(7/9)'],[6,6,'(8/9)']]);
 assert.equal(parts[0].lane,parts[1].lane);
 assert.equal(rows.filter(r=>r.event===family).length,1);
 assert.notEqual(rows.find(r=>r.event===family).lane,parts[0].lane);
 const tail=layoutWeek([work],['2027-01-03',null,null,null,null,null,null]);
 assert.equal(tail[0].progress,'(9/9)');
 const single={...event(3,dates[1],dates[1]),category:'work'};
 assert.equal(layoutWeek([single],dates)[0].progress,undefined);
});
