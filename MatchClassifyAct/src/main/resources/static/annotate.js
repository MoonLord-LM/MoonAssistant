"use strict";
const $ = id => document.getElementById(id);
const ACT_LABEL = { none:"无动作", click:"鼠标点击", other:"其它" };
const SHORT = n => (n.match(/^IMG_(\d{8})_(\d{6})/) || []).slice(1).join(" ") || n.replace(/\.png$/,"");

/* 分类标注将作为汇总分析产物目录名（summary/）：不允许文件系统非法符号（新输入时直接剔除），也不能以 . 结尾 */
const BAD_LABEL = /[\\/:*?"<>|\x00-\x1f]/;
const cleanLabel = s => s.replace(/[\\/:*?"<>|\x00-\x1f]/g, "");
const labelOk = s => !BAD_LABEL.test(s) && !/\.$/.test(s);

let ALL = [];          // 全部图片条目
let FILTER = "unmarked";
let curName = null;    // 当前展示图片名（可能不在当前筛选列表之外）
let dirty = false;
let naturalW = 0, naturalH = 0;
let mainMode = "fit";        // 主图显示模式：fit=自适应缩放（整幅可见并尽量占满：不足等比缩小、充足等比放大，不产生滚动条） / orig=原始分辨率 1:1
let actionSel = "none";
let px = null;         // {x,y} 图片像素（窗口相对坐标）
let loading = null;    // 当前图片名，用于防异步竞态
let lastMark = null;   // 上次输入/保存的标记草稿 {state,action,left,top}，切到未标注图时自动带入
let DEF = {};          // 分类定义表快照 {state: {action,left,top}}，来自 /api/annotate/defs（中心表：动作坐标每分类一份）
let stateFilter = null;   // 分类过滤状态：作用于「全部 / 已标注」视图；null=不过滤，字符串=只显示该分类

function listNow(){
  let L = FILTER === "all" ? ALL.slice() : ALL.filter(i => FILTER === "unmarked" ? !i.marked : i.marked);
  if(stateFilter && (FILTER === "all" || FILTER === "marked")){
    L = L.filter(i => i.marked && i.state === stateFilter);   // 「已标注」视图也可按分类过滤
  }
  if(FILTER === "all" || FILTER === "marked") L.reverse();   // 全部 / 已标注：最新在上；未标注：最旧在上（顺序打标）
  return L;
}
function curIndex(){ return listNow().findIndex(i => i.name===curName); }
function cur(){ const L = listNow(); const p = curIndex(); return p>=0 ? L[p] : null; }
function itemOf(name){ return ALL.find(i => i.name===name) || null; }
/* 拉取图片列表，按文件名时间戳升序作为内部基准序；视图展示方向由 listNow 按筛选决定 */
async function fetchAllSafe(){
  const resp = await fetch("/api/annotate/images");
  if(!resp.ok) throw new Error("HTTP " + resp.status);
  const arr = await resp.json();
  arr.sort((a,b)=> a.name < b.name ? -1 : 1);
  return arr;
}
function imgUrl(n){ return "/api/annotate/image/" + encodeURIComponent(n); }
function markUrl(n){ return "/api/annotate/mark/" + encodeURIComponent(n); }

/* 拉取分类定义表：中心表存每分类的统一定义动作/坐标（即使该分类暂无样本图也返回） */
async function fetchDefs(){
  const r = await fetch("/api/annotate/defs");
  if(!r.ok) throw new Error("HTTP " + r.status);
  const arr = await r.json();
  const m = {};
  for(const d of arr){
    if(d && d.state){
      m[d.state] = {
        action: (d.action && ACT_LABEL[d.action]) ? d.action : "none",
        left: typeof d.left === "number" ? d.left : null,
        top: typeof d.top === "number" ? d.top : null
      };
    }
  }
  return m;
}
/* 某分类是否已有统一定义（动作 click 时的坐标是否可直接采用） */
function defOf(state){ return DEF[state] || null; }

/* ---------------- 提示 ---------------- */
/* 历史日志：右下角出现过的消息全量保存到内存 LOG（弹窗回溯查看，与淡出展示互不影响）。
   普通消息在 toast()、截图/去重等在 showShotTip()、批量任务进度在 taskTip() 内统一入库（相同文本不重复）；
   meta 轮询还按 seq 增量把
   轮询间隙被节流的截图结果（shotLog）补齐，保证截图开启时每秒一拍的记录也不丢。 */
const KLOG_LABEL = { ok:"成功", err:"错误", skip:"跳过", warn:"注意" };
let LOG = [];                    // {at, txt, kind}，按发生先后追加（最早在前）
let lastShotLogSeq = -1;         // 已并入历史日志的最后一条截图结果 seq（-1 = 尚未取过，首次 meta 全量回填）
const LOG_RENDER_MAX = 8000;     // 弹窗列表最多渲染行数（LOG 数组本身全量保留，不受此限制）
const logKindOf = k => KLOG_LABEL[k] || "提示";
const fmtLogTime = t => {
  const d = new Date(t), p = n => String(n).padStart(2, "0");
  return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
};
function makeLogRow(it){
  const row = document.createElement("div");
  row.className = "logrow " + (it.kind || "info");
  const t = document.createElement("span"); t.className = "lt"; t.textContent = fmtLogTime(it.at);
  const k = document.createElement("span"); k.className = "lk"; k.textContent = "[" + logKindOf(it.kind) + "]";
  const x = document.createElement("span"); x.className = "lx"; x.textContent = it.txt;
  row.appendChild(t); row.appendChild(k); row.appendChild(x);
  return row;
}
function pushLog(txt, kind, at){
  if(!txt) return;
  const it = { at: at || Date.now(), txt: String(txt), kind: kind || "info" };
  LOG.push(it);
  const list = $("logList");                       // 面板开着才增量追加一行（最新在上）
  if(list){
    const empty = list.querySelector(".logempty");
    if(empty) empty.remove();
    list.insertBefore(makeLogRow(it), list.firstChild);
    while(list.children.length > LOG_RENDER_MAX) list.removeChild(list.lastChild);
  }
}
function openLogPanel(){
  if($("logModal")) return;
  const ov = document.createElement("div");
  ov.id = "logModal";
  ov.className = "modal-ov";
  ov.innerHTML =
    '<div class="xcard">' +
      '<div class="xhead">' +
        '<span class="xt2">历史日志</span>' +
        '<span class="xsub" id="logTotal"></span>' +
        '<span class="xsp"></span>' +
        '<button type="button" class="btn logclr" id="logClear">清空</button>' +
        '<button type="button" class="x" id="logClose" title="关闭（Esc）">✕</button>' +
      '</div>' +
      '<div class="loglist" id="logList"></div>' +
    '</div>';
  document.body.appendChild(ov);
  const list = $("logList");
  const total = $("logTotal");
  const renderAll = ()=>{
    list.innerHTML = "";
    if(!LOG.length){
      const e = document.createElement("div");
      e.className = "logempty";
      e.textContent = "暂无历史消息。右下角出现过的提示（截图保存 / 差异过小跳过、启动重复清理、标注与操作反馈等）都会完整记录在此，便于回溯查看。";
      list.appendChild(e);
      return;
    }
    const fr = document.createDocumentFragment();
    for(let i = LOG.length - 1; i >= Math.max(0, LOG.length - LOG_RENDER_MAX); i--){
      fr.appendChild(makeLogRow(LOG[i]));          // 最新在上：倒序渲染
    }
    list.appendChild(fr);
  };
  const refreshTotal = ()=> total.textContent = "共 " + LOG.length + " 条";
  const close = ()=>{
    ov.remove();
    document.removeEventListener("keydown", onKey);
  };
  const onKey = e => { if(e.key === "Escape") close(); };
  renderAll();
  refreshTotal();
  document.addEventListener("keydown", onKey);
  ov.addEventListener("click", e => { if(e.target === ov) close(); });   // 点弹层外（旁边）即关闭
  $("logClose").addEventListener("click", close);
  const clr = $("logClear");
  if(clr) clr.addEventListener("click", ()=>{
    LOG = [];
    renderAll();
    refreshTotal();
  });
}
function toast(msg, kind){
  pushLog(msg, kind || "info");                  // 右下角出现过的消息 → 历史日志
  const t = document.createElement("div");
  t.className = "toast " + (kind||"");
  t.textContent = msg;
  $("toasts").appendChild(t);
  setTimeout(()=>{ t.style.opacity="0"; t.style.transition="opacity .25s"; setTimeout(()=>t.remove(),260); }, 2600);
}

/* 右下角单条「截图结果」提示：截图按“每帧处理完约 1s 一拍”（保存 / 差异过小丢弃），比普通 toast 频密，
   故用单条替换式展示——新结果直接替换旧内容，约 2 秒后自动淡出，不叠加刷屏 */
let shotTipTimer = 0;
function showShotTip(msg, kind){
  pushLog(msg, kind || "info");                  // 右下角单条替换式消息（截图/去重等）→ 历史日志
  const box = $("toasts");
  let el = box.querySelector(".toast.shot");
  if(!el){
    el = document.createElement("div");
    el.className = "toast shot " + (kind||"");
    box.appendChild(el);
  }else{
    el.className = "toast shot " + (kind||"");
    el.style.transition = "none";     // 先取消淡出过渡，立即恢复显示
    el.style.opacity = "1";
  }
  el.textContent = msg;
  clearTimeout(shotTipTimer);
  shotTipTimer = setTimeout(()=>{
    el.style.transition = "opacity .25s";
    el.style.opacity = "0";
    setTimeout(()=> el.remove(), 280);
  }, 2000);
}

/* 右下角单条「后台任务进度」提示：自动分析 / 全量重建等批量任务由轮询反复刷新当前进度时使用。
   新进度直接替换旧内容，任务结束才淡出；进度文本每次变化写入历史日志（相同文本的重复轮询不重复入库，
   避免一次任务几十条同文刷屏），任务的开始 / 完成 / 失败仍由 toast() 正常入库记录 */
let taskTipTimer = 0;
let lastTaskTipLog = "";        // 上一次已写入历史日志的任务提示文本
function taskTip(msg, kind){
  const box = $("toasts");
  let el = box.querySelector(".toast.task");
  if(msg == null){
    if(el){
      clearTimeout(taskTipTimer);
      el.style.transition = "opacity .25s";
      el.style.opacity = "0";
      setTimeout(()=> el.remove(), 280);
    }
    return;
  }
  if(msg !== lastTaskTipLog){          // 文本变化 → 记入历史日志
    pushLog(msg, kind || "info");
    lastTaskTipLog = msg;
  }
  if(!el){
    el = document.createElement("div");
    el.className = "toast task " + (kind || "");
    box.appendChild(el);
  }else{
    el.className = "toast task " + (kind || "");
    el.style.transition = "none";     // 先取消淡出过渡，立即恢复显示
    el.style.opacity = "1";
  }
  el.textContent = msg;
  clearTimeout(taskTipTimer);
  taskTipTimer = setTimeout(()=>{
    el.style.transition = "opacity .25s";
    el.style.opacity = "0";
    setTimeout(()=> el.remove(), 280);
  }, 1600);
}

/* ---------------- 列表加载 / 渲染 ---------------- */
async function loadList(preferName){
  let arr;
  try{ arr = await fetchAllSafe(); }catch(e){ toast("列表加载失败：" + e.message, "err"); return; }
  ALL = arr;
  try{ DEF = await fetchDefs(); }catch(_){ /* 列表优先，定义表下次刷新再试 */ }
  rebuildStates();
  if(ALL.length===0){ renderList(); showEmpty(); return; }
  let target = preferName ? itemOf(preferName) : null;
  if(!target){ const um = ALL.find(i=>!i.marked); target = um || ALL[0]; }
  selectTarget(target.name);
}

/* ---------------- 分类标签 chip：未标注 / 已标注 / 全部 三处共用同一结构、样式与交互 ----------------
   一个 chip 分三个可点区域：✎ = 整体重命名；数字 = 按该分类过滤左列表
   （「全部 / 已标注」过滤均在本视图内进行；「未标注」点数字转「全部」查看）；文本 = 全部过滤 / 未标注、已标注“选中作为设定”。
   高亮：on（绿）= 文本已被选为当前分类标注；fil（蓝）= 列表正按该分类过滤。 */
function makeTagChip(box, state, count, o){
  const t = document.createElement("span");
  t.className = "tag";
  if(o && o.sel) t.classList.add("on");
  if(o && o.fil) t.classList.add("fil");
  const label = state === null ? "全部" : state;
  const txt = document.createElement("span");
  txt.className = "txt";
  txt.textContent = label;
  txt.title = state === null
    ? "当前未按分类过滤，点此保持显示全部截图"
    : (FILTER === "all"
      ? "让左侧只显示「" + state + "」分类的截图（再点一次取消过滤；与点数字一致）"
      : "把当前图片的分类标注设为「" + state + "」（自动带出该分类统一的动作与点击坐标）");
  const n = document.createElement("span");
  n.className = "cnt";
  n.textContent = count;
  n.title = state === null
    ? ("共 " + count + " 张截图，点此取消过滤显示全部")
    : (count + " 张已标注截图在使用该分类；点此让左侧列表只显示该分类");
  t.appendChild(txt);
  t.appendChild(n);
  if(state !== null){
    const ed = document.createElement("button");
    ed.type = "button";
    ed.className = "ed";
    ed.title = "整体重命名该分类：样本、产物目录与动作定义一并迁移";
    ed.setAttribute("aria-label", "整体重命名分类 " + state);
    ed.textContent = "✎";
    ed.addEventListener("click", (e)=>{
      e.stopPropagation();
      startRenameTag(state, t);
    });
    t.appendChild(ed);
  }
  txt.addEventListener("click", ()=> {
    if(state === null){ goFilter(null); return; }        // 「全部」chip：取消过滤，显示全部截图
    if(FILTER === "all"){ goFilter(state); return; }     // 「全部」视图：点文本与点数字一致 = 按该分类过滤
    setAsLabel(state);                                    // 未标注 / 已标注视图：把该分类设为当前图标注
  });
  n.addEventListener("click", ()=> goFilter(state));
  box.appendChild(t);
}

/* 未标注 / 已标注视图：把“已用过”的分类标签渲染成输入框上方的可点标签（文本升序，与「全部」视图一致） */
function rebuildStates(){
  const box = $("stateTags"); box.innerHTML = "";
  const counts = new Map();
  for(const i of ALL){ if(i.marked && i.state){ counts.set(i.state, (counts.get(i.state)||0) + 1); } }
  const curVal = $("stateInput").value.trim();
  for(const s of [...counts.keys()].sort((a,b)=>a.localeCompare(b,"zh"))){
    makeTagChip(box, s, counts.get(s), { sel: s === curVal, fil: FILTER === "marked" && stateFilter === s });
  }
}

/* 高亮与输入框当前内容一致的标签 chip */
function updateTagActive(){
  const val = $("stateInput").value.trim();
  for(const t of $("stateTags").children){
    const txt = t.querySelector(".txt");
    t.classList.toggle("on", !!(txt && txt.textContent === val));
  }
  updateHints();
}

/* ---------------- 分类 chip / 智能建议一键填入：联动动作与坐标 ----------------
   只把文本写进输入框会让“该分类已统一为鼠标点击”的情况无法直接保存（还差坐标）。
   因此填入分类标注时，从该分类已标注样本（排除当前图）自动带入：
   动作取样本中占多数的动作；若动作为鼠标点击，坐标取该分类点击样本中出现次数最多的点
   （同一分类截图同窗口尺寸、画面一致，点击点应固定，个别历史异位点不会带偏）。
   尚无任何样本的分类只填文本、保持当前动作选择（首次标注的动作在保存时才固定）。
   自动带入后仍可在图上单击微调坐标。 */
function categoryProbe(state, excludeName){
  const list = ALL.filter(i => i.marked && i.state === state && i.name !== excludeName);
  if(!list.length) return null;
  const cntA = new Map();
  for(const i of list){ const a = i.action || "none"; cntA.set(a, (cntA.get(a)||0) + 1); }
  const action = [...cntA.entries()].reduce((a,b)=> (b[1] > a[1] ? b : a))[0];
  let point = null;
  if(action === "click"){
    const cnt = new Map();
    for(const i of list){
      if(i.action === "click" && typeof i.left === "number" && typeof i.top === "number"){
        const k = i.left + "," + i.top;
        cnt.set(k, (cnt.get(k)||0) + 1);
      }
    }
    if(cnt.size){
      const best = [...cnt.entries()].reduce((a,b)=> (b[1] > a[1] ? b : a))[0].split(",");
      point = { x:Number(best[0]), y:Number(best[1]) };
    }
  }
  return { action, point };
}

function adoptCategory(state){
  const sameState = $("stateInput").value.trim() === state;
  $("stateInput").value = state;
  let changed = !sameState;
  // 优先取同分类样本众数；样本缺失（或正在编辑本图）时回退中心表定义
  const def = defOf(state);
  const probe = categoryProbe(state, curName) ||
    (def ? { action:def.action, point: (def.action === "click" && def.left != null && def.top != null) ? {x:def.left, y:def.top} : null } : null);
  if(probe){
    if(actionSel !== probe.action){ setAction(probe.action, false); changed = true; }
    const want = probe.action === "click" ? probe.point : null;
    const samePx = want ? !!(px && px.x === want.x && px.y === want.y) : !px;
    if(!samePx){ px = want; changed = true; }
  }
  renderDot();
  updateTagActive();
  updateHints();
  if(changed){ setDirty(); }
}

/* ---------------- 分类标注重命名（chip 原地变成输入框，不弹浏览器 prompt） ---------------- */
let renChip = null;     // 正处于改名编辑的 chip 元素（含原节点的 txt/cnt/✎）
let renFrom = "";       // 它的原分类标注名

/* 退出编辑并恢复 chip 原样（原节点始终保留，✎ 点击监听不会丢） */
function exitRenameEdit(){
  if(!renChip) return;
  const chip = renChip;
  renChip = null; renFrom = "";
  chip.classList.remove("editing");
  const inp = chip.querySelector("input.rename-in");
  if(inp) inp.remove();
}

/* 点 chip 内 ✎：chip 原地显示输入框，回车提交、Esc 或失焦取消 */
function startRenameTag(from, chip){
  if(renChip){
    if(renChip === chip){ exitRenameEdit(); return; }   // 同 chip 再点 ✎：退出编辑
    exitRenameEdit();                                    // 别的 chip 在编辑：先取消它
  }
  renFrom = from;
  renChip = chip;
  chip.classList.add("editing");
  const inp = document.createElement("input");
  inp.type = "text";
  inp.className = "rename-in";
  inp.value = from;
  inp.addEventListener("click", e => e.stopPropagation());                 // 别把它当作选中 chip 的点击
  inp.addEventListener("keydown", e => {
    e.stopPropagation();
    if(e.key === "Enter"){ e.preventDefault(); commitRename(from, inp); }
    else if(e.key === "Escape"){ e.preventDefault(); exitRenameEdit(); }
  });
  inp.addEventListener("blur", ()=>{ if(renChip === chip) exitRenameEdit(); });
  chip.appendChild(inp);
  inp.focus();
  inp.select();
}

/* 提交改名：后端批量更新该分类全部标注 json 的 state，并把旧 summary/ 产物目录整体迁名为新名 */
async function commitRename(from, inp){
  const chip = renChip;
  const to = inp.value.trim();
  if(!to){ exitRenameEdit(); return; }
  if(to === from){ exitRenameEdit(); return; }
  if(!labelOk(to)){
    toast("分类标注不能包含 \\ / : * ? \" < > | 等文件名字符，也不能以 . 结尾", "err");
    if(renChip === chip){ inp.focus(); inp.select(); }   // 保留输入内容让用户修改
    return;
  }
  let msg;
  try{
    const r = await fetch("/api/annotate/rename", {
      method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ from, to })
    });
    if(!r.ok){
      msg = "HTTP " + r.status; try{ msg = await r.text(); }catch(_){}
      toast("重命名失败：" + msg, "err");
      if(renChip === chip){ inp.focus(); inp.select(); } // 仍在编辑态则保留输入内容
      return;
    }
    const j = await r.json();
    const n = Number(j && j.updated) || 0;
    exitRenameEdit();                                   // 成功：先收起输入框，再整树重建
    for(const i of ALL){ if(i.marked && i.state === from){ i.state = to; } }
    if(DEF[from]){ DEF[to] = DEF[from]; delete DEF[from]; }   // 定义表的 key 也随改名迁移
    if(stateFilter === from){ stateFilter = to; }   // 「全部」视图正在按旧名过滤时，改完后继续按新名过滤
    if($("stateInput").value.trim() === from){ $("stateInput").value = to; setDirty(); }
    rebuildStates();
    renderList();
    updateTagActive();
    toast("已将 " + n + " 张图的分类标注「" + from + "」改为「" + to + "」", "ok");
  }catch(e){
    toast("重命名失败：" + (e && e.message ? e.message : msg), "err");
    if(renChip === chip){ inp.focus(); inp.select(); }
  }
}

/* ---------------- 唯一性约束：同一分类标注只对应一种匹配动作 ---------------- */
function actLabel(a){ return ACT_LABEL[a] || a; }
function stateUsage(state, excludeName){
  const u = { count:0, actions:new Set() };
  for(const i of ALL){
    if(!i.marked || !i.state || i.state !== state) continue;
    if(excludeName && i.name === excludeName) continue;
    u.count++;
    u.actions.add(i.action && ACT_LABEL[i.action] ? i.action : "none");
  }
  return u;
}
/* 返回冲突原因（null = 可以保存）。仅“该分类名下仍有已标注样本”时才受唯一动作约束；
   中心表可能残留自历史样本的定义（样本删光后仍保留）——空分类可被本次首次标注覆盖 */
function actionConflict(state, act, excludeName, redefine){
  if(!state || redefine) return null;
  const def = defOf(state);
  const u = stateUsage(state, excludeName);
  if(def && def.action !== act && u.count > 0){
    return "「" + state + "」已定义动作「" + actLabel(def.action) + "」"
      + (def.action === "click" && def.left != null ? "（点击坐标 " + def.left + "," + def.top + "）" : "")
      + "；同一分类标注只对应一种动作/坐标，请改动作或换分类标注（如需重定义，请打开一张已标注该分类的图修改并保存）。";
  }
  if(u.count > 0 && !u.actions.has(act)){
    return "「" + state + "」已被 " + u.count + " 张图使用，匹配动作统一为「"
      + [...u.actions].map(actLabel).join(" / ") + "」；同一分类标注只对应一种匹配动作，请改动作或换分类标注。";
  }
  return null;
}
/* 编辑区下的实时提示：首个使用 / 已一致 / 冲突 */
function updateHints(){
  const el = $("actHint"); if(!el) return;
  const state = $("stateInput").value.trim();
  let cls = "", msg = "";
  if(state){
    const def = defOf(state);
    const curItem = cur();
    const redefHere = !!(curItem && curItem.marked && curItem.state === state);   // 本图已属该分类：改动=重定义分类
    const u = stateUsage(state, curName);
    const vacant = !!def && u.count === 0;   // 有历史定义但当前无样本：可被首次标注重新定义
    if(def){
      if(redefHere && def.action !== actionSel){
        cls = "show info";
        msg = "本图属于「" + state + "」，把动作改为「" + actLabel(actionSel) + "」并保存会重定义该分类（动作/坐标全组同步，保存前会再次确认）。";
      } else if(def.action !== actionSel && !vacant){
        cls = "show bad";
        msg = "冲突：「" + state + "」分类定义动作是「" + actLabel(def.action) + "」，同一分类只对应一种动作/坐标；请改动作或换分类标注（如需重定义，请打开一张已标注该分类的图修改并保存）。";
      } else if(def.action !== actionSel){
        cls = "show info";
        msg = "「" + state + "」旧定义是「" + actLabel(def.action) + "」但当前已无样本图，本次保存将把它重新定义为「" + actLabel(actionSel) + "」" + (actionSel === "click" ? "（需先在图上点选坐标）" : "") + "。";
      } else if(vacant){
        cls = "show ok";
        msg = "「" + state + "」定义沿用于「" + actLabel(def.action) + "」，本次保存补入本图样本。";
      } else {
        cls = "show ok";
        msg = "「" + state + "」已定义动作「" + actLabel(def.action) + "」。";
      }
    } else if(u.count === 0){
      cls = "show info";
      msg = "「" + state + "」首次标注，所选动作将固定为该分类的唯一动作" + (actionSel === "click" ? "（需先在图上点选坐标）" : "") + "。";
    } else if(u.actions.size === 1){
      const one = [...u.actions][0];
      if(one === actionSel){
        cls = "show ok";
        msg = "「" + state + "」已用于 " + u.count + " 张图，动作「" + actLabel(one) + "」一致。";
      } else {
        cls = "show bad";
        msg = "冲突：「" + state + "」已统一为「" + actLabel(one) + "」，请改动作或换分类标注。";
      }
    } else {
      cls = "show bad";
      msg = "「" + state + "」历史动作混杂（" + [...u.actions].map(actLabel).join(" / ") + "），建议先统一。";
    }
  }
  el.className = "hint " + cls;
  el.textContent = msg;
}

/* 把数量直接写进筛选按钮：全部（N）未标注（N）已标注（N）汇总分析（N） */
function setSegState(){
  const nAll = ALL.length, nUn = ALL.filter(i=>!i.marked).length;
  const keys = new Set();
  for(const i of ALL){
    if(i.marked && i.state) keys.add(i.state + "\u0000" + (i.action || "none"));   // 与服务端 groups() 同口径：(state, action) 去重
  }
  const cnt = { all:nAll, unmarked:nUn, marked:nAll - nUn, think:keys.size };
  const names = { all:"全部", unmarked:"未标注", marked:"已标注", think:"汇总分析" };
  for(const b of $("filterSeg").querySelectorAll("button")){
    b.classList.toggle("on", b.dataset.f === FILTER);
    b.textContent = names[b.dataset.f] + "（" + cnt[b.dataset.f] + "）";
  }
}

function renderList(){
  if(FILTER === "think"){ renderThinkList(); return; }
  const L = listNow();
  const ul = $("imgList"); ul.innerHTML = "";
  $("listCount").textContent = L.length + " 张";
  $("lstTitle").textContent = stateFilter && (FILTER === "all" || FILTER === "marked")
    ? "「" + stateFilter + "」分类" + (FILTER === "marked" ? "（已标注）" : "截图")
    : (FILTER === "unmarked" ? "截图列表（最旧在上）" : "截图列表（最新在上）");
  if(FILTER === "all") renderFilterPanel();   // 分类过滤面板跟随最新计数刷新
  const curItem = cur();
  for(const item of L){
    const li = document.createElement("li"); li.className="row" + (curItem && curItem.name===item.name ? " on" : "");
    const preview = item.marked
      ? (item.state || "(无分类标注)") + (item.action && item.action!=="none" ? " ｜ " + (ACT_LABEL[item.action]||item.action) + (item.action==="click" && item.left!=null ? `(${item.left},${item.top})` : "") : "")
      : "尚未标记";
    li.innerHTML =
      `<div class="r1"><span class="t">${SHORT(item.name)}</span><span class="chip ${item.marked?'m':'u'}">${item.marked?'已标记':'未标记'}</span></div>` +
      `<div class="r2">${preview.replace(/</g,"&lt;")}</div>`;
    li.addEventListener("click", ()=> changeTo(item.name));
    ul.appendChild(li);
  }
  if(!L.length){
    const d=document.createElement("li"); d.className="empty";
    // 目录里根本没有图片 → 统一说「没有图片」；有图片时才按当前筛选给具体提示
    d.textContent = ALL.length === 0 ? "没有图片"
      : FILTER === "marked" ? (stateFilter ? "「" + stateFilter + "」分类下暂无已标注截图" : "还没有已标记的图片")
      : FILTER === "all" && stateFilter ? "「" + stateFilter + "」分类下暂无截图"
      : "🎉 全部图片都已标记";
    ul.appendChild(d);
  }
  setSegState();   // 筛选按钮文案 + 选中态一起刷新
  refreshJumpBar();   // 「全部」视图底部「修改这张图」入口随当前图/选中态刷新
}

/* ---------------- 「全部」视图：分类标签（文本=设定当前图标注；数字=过滤；✎=重命名） ---------------- */
function renderFilterPanel(){
  const box = $("catList"); if(!box) return;
  const counts = new Map();
  for(const i of ALL){ if(i.marked && i.state) counts.set(i.state, (counts.get(i.state)||0)+1); }
  box.innerHTML = "";
  makeTagChip(box, null, ALL.length, { fil: stateFilter === null });   // 「全部」：高亮 = 当前未按分类过滤
  for(const s of [...counts.keys()].sort((a,b)=>a.localeCompare(b,"zh"))){
    makeTagChip(box, s, counts.get(s), { fil: stateFilter === s });    // 高亮 = 列表正按该分类过滤
  }
}

function setStateFilter(state){
  if(FILTER !== "all" && FILTER !== "marked"){ stateFilter = null; return; }
  if(state === stateFilter) state = null;   // 再次点击当前分类 = 取消过滤
  const old = stateFilter;
  stateFilter = state;
  if(FILTER === "marked") rebuildStates();  // 标注视图：chips 蓝底跟随过滤状态
  const L = listNow();
  if(!L.length){
    if(dirty){ stateFilter = old; rebuildStates(); return; }   // 有未保存编辑时不因过滤清屏
    curName = null;
    renderList();
    showEmpty(state
      ? (FILTER === "marked" ? "「" + state + "」分类下暂无已标注截图。" : "「" + state + "」分类下暂无截图。")
      : "");
    return;
  }
  const keep = itemOf(curName);
  const next = keep && L.some(i => i.name === keep.name) ? keep.name : L[0].name;
  if(dirty && next !== curName && !confirm("当前标注尚未保存，确定切换到其他图片？")){
    stateFilter = old; rebuildStates(); return;
  }
  selectTarget(next);
}

/* 点标签“文本”：把该分类设为当前图片的分类标注（“选中作为设定”）。
   未标注/已标注视图直接填入编辑器并带出该分类统一动作/坐标；「全部」视图先跳到该图所属编辑视图再填入。 */
function setAsLabel(state){
  if(state === null){ goFilter(null); return; }
  if(!cur()){ toast("请先在左侧选择一张图片，再点标签文本设定分类标注。", "warn"); return; }
  if(FILTER === "unmarked" || FILTER === "marked"){ adoptCategory(state); return; }
  if(FILTER === "all" && jumpToEdit()){ adoptCategory(state); }
}

/* 点标签“数字”：按该分类过滤左侧列表（再次点击同一分类 = 取消过滤）。
   「全部 / 已标注」视图在本视图内过滤：已标注视图停留并跳到该分类第一张，不再切去「全部」；
   「未标注」视图本身不含已标注样本，点数字仍切到「全部」查看该分类。 */
async function goFilter(state){
  if(FILTER === "all" || FILTER === "marked"){ setStateFilter(state); return; }
  if(FILTER === "unmarked"){
    await applyFilter("all");             // 复用顶部视图切换逻辑（含未保存确认 / 列表刷新）
    if(FILTER === "all") setStateFilter(state);
  }
}

/* 顶部「全部 / 未标注 / 已标注 / 汇总分析」视图切换的公共逻辑（按钮与标签数字过滤共用） */
async function applyFilter(f){
  if(FILTER === f) return;
  if(dirty && !confirm("当前标注尚未保存，确定切换？")) return;
  if(f === "think"){ enterThink(); return; }   // 汇总分析入口自带 refreshThink 全量刷新
  if(FILTER === "think"){ exitThink(); curName = null; }
  FILTER = f;
  if(f !== "all") stateFilter = null;        // 分类过滤只属于「全部」视图，离开即重置
  if(f === "unmarked" || f === "marked") rebuildStates();   // 标注视图：chips 的 sel/fil 随新视图刷新
  syncSugDock();                             // dock 浮层按当前是否有提示内容显示/隐藏（浮层不占布局）
  syncRightPanel();                          // 右栏随视图切换：all→分类标签 / unmarked、marked→标注编辑
  if(!dirty) await refreshSilent();          // 切到新视图前先把列表/计数同步到最新：挂机期间落盘的新截图即刻出现
  const name = cur() ? cur().name : null;
  if(name && listNow().some(i=>i.name===name)){ renderList(); updateNavButtons(); }
  else if(listNow().length){ selectTarget(listNow()[0].name); }
  else { renderList(); showEmpty(); }
  refreshSmartTip();   // 视图切换后，按当前目标图刷新智能分析提示条（建议条只出现在「未标注」）
}

/* 按当前视图切换右栏：think → 汇总分析；unmarked/marked → 标注编辑；all → 分类过滤 + 底部「修改这张图」入口 */
function syncRightPanel(){
  if(appMode !== "mark") return;
  $("edThink").style.display = FILTER === "think" ? "" : "none";
  $("edNorm").style.display = (FILTER === "unmarked" || FILTER === "marked") ? "" : "none";
  $("edFilter").style.display = FILTER === "all" ? "" : "none";
  $("edJump").style.display = FILTER === "all" ? "" : "none";
  const ed = document.querySelector("aside.editor");
  if(ed) ed.classList.toggle("jump-mode", FILTER === "all");   // all：内容置顶、「修改这张图」紧随分类过滤之下
  if(FILTER === "all") renderFilterPanel();
  refreshJumpBar();
}

/* 「全部」视图右栏「修改这张图」入口（分类过滤标签下方）：随当前选中图刷新目标视图（已标注 → 去「已标注」改；未标注 → 去「未标注」补） */
function refreshJumpBar(){
  const btn = $("btnJumpMark");
  if(!btn) return;
  const item = itemOf(curName);
  btn.disabled = !item;
  if(!item){
    btn.textContent = "修改这张图的标记";
    btn.title = "选择一张截图后可一键跳去对应视图修改";
    return;
  }
  btn.textContent = item.marked ? "修改此图（已标注视图）" : "标注此图（未标注视图）";
  btn.title = item.marked
    ? "跳转到「已标注」视图并定位这张图，可直接改分类标注 / 匹配动作 / 鼠标点击坐标后保存"
    : "跳转到「未标注」视图并定位这张图，可补全分类标注 / 匹配动作 / 鼠标点击坐标后保存";
}

/* 从「全部」浏览视图一键跳转编辑：按当前图状态切到「未标注 / 已标注」视图并定位该图（右栏随即变为标注编辑）。
   返回是否跳转成功（供“点标签文本设定标注”流程判断是否继续填入分类）。 */
function jumpToEdit(){
  const btn = $("btnJumpMark");
  const item = itemOf(curName);
  if(!item || !btn || btn.disabled) return false;
  if(dirty && !confirm("当前标注尚未保存，确定跳转到编辑视图？")) return false;
  const f = item.marked ? "marked" : "unmarked";
  if(FILTER === f) return false;
  FILTER = f;
  stateFilter = null;                 // 分类过滤只属于「全部」视图，离开即重置
  rebuildStates();                    // 标注视图：chips 的 sel/fil 随视图重置
  syncSugDock();
  syncRightPanel();                   // 右栏：分类过滤 → 标注编辑
  selectTarget(item.name);            // 定位到当前这张图并载入标注，可直接点图改坐标
  refreshSmartTip();
  toast(item.marked
    ? "已跳到「已标注」视图：可直接修改此图的分类标注 / 匹配动作 / 鼠标点击坐标（在图上点一下可微调）"
    : "已跳到「未标注」视图：可直接补全此图的分类标注 / 匹配动作 / 鼠标点击坐标", "ok");
  return true;
}

function showEmpty(msg){
  curName = null;
  $("imgwrap").style.display = "none";
  $("placeholder").style.display = "";
  showZoomCtl(false);
  resetZoom();
  $("placeholder").textContent = msg || "没有图片。截图任务开启后，新截图会自动出现并同步到本列表。";
  $("fname").textContent = ""; $("fsub").textContent = ""; $("imgDims").textContent = "";
  $("stateInput").value = ""; setAction("none"); px=null; renderDot();
  updateTagActive();
  updateNavButtons();
  refreshSmartTip();          // 无图片 → 隐藏智能分析提示条
  setEditorEnabled(false);    // 无当前图 → 右侧编辑按钮置灰
}

/* ---------------- 选择 / 展示当前图 ---------------- */
function selectTarget(name){
  const item = itemOf(name);
  curName = name;
  renderList();
  if(item) showImage(item); else showEmpty();
}

function changeTo(name){
  if(dirty && !confirm("当前标注尚未保存，确定放弃修改？")) return;
  selectTarget(name);
}

async function showImage(item, opts){
  if(!item){ showEmpty(); return; }
  setEditorEnabled(true);        // 有可操作图片 → 右侧编辑按钮恢复可用
  const prefill = !(opts && opts.prefill === false);   // false = 严格还原（重新加载）
  loading = item.name;
  dirty = false;
  // 重置编辑器
  // 顶栏信息精简：左侧列表已按时间排序且每行标题带时间，这里只保留「完整文件名 + 分辨率」两项
  $("fname").textContent = item.name;
  $("fsub").textContent = "";
  $("imgwrap").style.display = "";
  $("placeholder").style.display = "none";
  $("imgDims").textContent = "";   // 待图片加载完成后按实际分辨率填充
  naturalW = naturalH = 0;
  px = null;
  resetZoom();                 // 切换图片回到「自适应缩放」，并从顶栏显示缩放控件
  showZoomCtl(true);
  $("mainImg").removeAttribute("src");
  $("mainImg").src = imgUrl(item.name);
  $("stateInput").value = "";
  setAction(item.action && ACT_LABEL[item.action] ? item.action : "none", false);
  renderDot();
  updateNavButtons();
  refreshSmartTip();          // 未标注图 → 智能分析相似度建议条；已标注图 → 隐藏
  // 读标注
  try{
    const r = await fetch(markUrl(item.name));
    if(!r.ok) throw new Error("HTTP " + r.status);
    const m = await r.json();
    if(loading !== item.name) return;   // 已切走
    if(item.marked){
      applyBodyToEditor(m, false);            // 已标注：显示该图自己的标记
    } else if(prefill && lastMark){
      applyBodyToEditor(lastMark, false);     // 未标注：自动带入上次的标记
    } else {
      applyBodyToEditor({state:"", action:"none"}, false);
    }
  }catch(e){
    if(loading === item.name) toast("读取标注失败（按未标注处理）：" + e.message, "err");
  }
  // 「全部」视图只做浏览与按分类过滤，不编辑标注：收起取点、清掉自动带入的草稿，避免误改/误存
  if(FILTER === "all"){
    $("stateInput").value = "";
    setAction("none", false);
    px = null;
    renderDot();
    updateTagActive();
  }
}

$("mainImg").addEventListener("load", ()=>{
  const img = $("mainImg");
  naturalW = img.naturalWidth || 0;
  naturalH = img.naturalHeight || 0;
  $("imgDims").innerHTML = naturalW && naturalH
    ? `尺寸 <b>${naturalW} × ${naturalH}</b> 像素`
    : "";
  mainMode = "fit";        // 每张图默认进入「自适应缩放」
  resetZoom();
  renderDot();
});
$("mainImg").addEventListener("error", ()=>{
  toast("图片加载失败：" + (cur()?cur().name:"") , "err");
});

/* ---------------- 主图缩放：原始分辨率 / 自适应缩放 ---------------- */
function showZoomCtl(show){ $("zoomCtl").classList.toggle("show", !!show); }

/* 自适应缩放预留的安全余量（px）：可用区整体内缩后再等比放大/缩小，
   可吸收 1px 边框与亚像素舍入造成的临界溢出，保证贴满也绝不出现滚动条；
   “原始分辨率”模式不受影响（1:1 放不下时仍按设计可滚动） */
const FIT_GAP_PX = 8;

/* 当前可显示区尺寸（imgarea 去掉内边距后的内容区，再预留 FIT_GAP_PX 安全余量） */
function imgAreaBox(){
  const el = $("imgarea"), cs = getComputedStyle(el);
  const padL = parseFloat(cs.paddingLeft) || 0, padR = parseFloat(cs.paddingRight) || 0;
  const padT = parseFloat(cs.paddingTop) || 0, padB = parseFloat(cs.paddingBottom) || 0;
  return {
    w: Math.max(40, el.clientWidth - padL - padR - FIT_GAP_PX),
    h: Math.max(40, el.clientHeight - padT - padB - FIT_GAP_PX)
  };
}

/* 两种模式对应的显示比例 */
function mainScale(){
  if(!naturalW || !naturalH) return 0;
  if(mainMode === "orig") return 1;                                 // 原始分辨率：1:1 原像素
  const A = imgAreaBox();
  // 自适应缩放 = 整幅可见且尽量占满可用区：空间不足等比缩小、空间充足等比放大到可放下的最大尺寸；
  // 四周保留舞台内边距的少量留白；floor 后不超出显示区，始终不产生滚动条
  return Math.min(A.w / naturalW, A.h / naturalH);
}

function applyMainZoom(){
  const img = $("mainImg"), st = img.style, wrap = $("imgwrap");
  const s = mainScale();
  if(!s){
    st.removeProperty("width"); st.removeProperty("height");
    st.removeProperty("maxWidth"); st.removeProperty("maxHeight");
    img.style.imageRendering = "auto";
    wrap.classList.remove("zoomed");
    syncZoomCtl();
    return;
  }
  st.setProperty("maxWidth", "none");
  st.setProperty("maxHeight", "none");
  const w = Math.max(1, Math.floor(naturalW * s));  // floor：保证不超出显示区，不产生滚动条
  const h = Math.max(1, Math.floor(naturalH * s));
  st.width = w + "px";
  st.height = h + "px";
  img.style.imageRendering = "auto";   // 平滑缩放（缩小或放大）、原始分辨率 1:1，无需像素锐化
  wrap.classList.toggle("zoomed", mainMode !== "fit");
  if(mainMode === "fit"){
    $("imgarea").scrollLeft = 0;
    $("imgarea").scrollTop = 0;
  }
  syncZoomCtl();
  renderDot();       // 缩放后标记点/辅助线按新显示尺寸重新定位
}

function setMainMode(m){
  if(m !== "orig" && m !== "fit") return;
  if(!naturalW) return;
  mainMode = m;
  applyMainZoom();
}

/* 直接点图片：在「自适应缩放 ↔ 原始分辨率」之间切换（鼠标点击模式下用于取坐标） */
function toggleMainMode(){
  if(!naturalW) return;
  setMainMode(mainMode === "orig" ? "fit" : "orig");
}

function resetZoom(){
  mainMode = "fit";
  applyMainZoom();
  $("imgarea").scrollLeft = 0;
  $("imgarea").scrollTop = 0;
}

function syncZoomCtl(){
  const ids = { orig:"zmOrig", fit:"zmFit" };
  for(const m in ids){
    const b = $(ids[m]);
    if(b) b.classList.toggle("on", mainMode === m);
  }
  const pct = $("zoomPct"), s = mainScale();
  if(pct) pct.textContent = mainMode === "orig" ? "100%" : (s ? Math.round(s * 100) + "%" : "—");
}

/* ---------------- 动作 / 坐标 ---------------- */
function setAction(a, markDirty){
  if(a!=="click") a = "none";        // 仅保留 无动作 / 鼠标点击 两种
  actionSel = a;
  document.body.dataset.mode = a;
  document.querySelectorAll(".act").forEach(el=> el.classList.toggle("on", el.dataset.a===a));
  const radios = document.querySelectorAll('input[name=action]');
  for(const r of radios){ if(r.value===a) r.checked = true; }
  $("coordBox").style.display = a==="click" ? "block" : "none";
  if(a!=="click" && px){ px = null; }
  renderDot();
  updateHints();
  if(markDirty){ setDirty(); }
}

/* 把一组标记填入编辑区；markDirty=true 时标记为已修改（需保存） */
function applyBodyToEditor(b, markDirty){
  const state = (b && b.state) || "";
  const act = (b && b.action && ACT_LABEL[b.action]) ? b.action : "none";
  $("stateInput").value = state;
  setAction(act, false);
  if(b && b.action === "click" && typeof b.left === "number" && typeof b.top === "number"){
    px = { x:b.left, y:b.top };
  } else { px = null; }
  renderDot();
  updateTagActive();
  if(markDirty){ setDirty(); }
}

function toCss(){
  if(!px || !naturalW) return null;
  const img = $("mainImg");
  return {
    x: (px.x + 0.5) * img.clientWidth  / naturalW,
    y: (px.y + 0.5) * img.clientHeight / naturalH
  };
}

function renderDot(){
  const dot = $("dot"), v = $("vline"), h = $("hline");
  const show = actionSel==="click" && px;
  dot.style.display = show ? "block" : "none";
  v.style.display = h.style.display = show ? "block" : "none";
  $("coordBox").style.display = actionSel==="click" ? "block" : "none";
  if(show){
    $("coordText").textContent = `(${px.x}, ${px.y})`;
    const c = toCss();
    if(!c){             // 图片尚未加载完成（naturalW 未知）：先如实显示坐标文本，点位待图片 load 后再绘
      dot.style.display = v.style.display = h.style.display = "none";
      return;
    }
    dot.style.left = c.x + "px"; dot.style.top = c.y + "px";
    v.style.left = c.x + "px"; h.style.top = c.y + "px";
  } else {
    $("coordText").textContent = "—";
  }
}

$("mainImg").addEventListener("click", (e)=>{
  if(actionSel === "click"){
    // 「鼠标点击」模式：单击用于取坐标点（坐标换算按当前显示尺寸，放大后更可精确定位）
    if(!naturalW){ toast("图片尚未加载完成", "err"); return; }
    const img = $("mainImg");
    const r = img.getBoundingClientRect();
    if(r.width<=0 || r.height<=0) return;
    const nx = Math.max(0, Math.min(naturalW-1, Math.round((e.clientX - r.left) / r.width * naturalW)));
    const ny = Math.max(0, Math.min(naturalH-1, Math.round((e.clientY - r.top) / r.height * naturalH)));
    px = { x:nx, y:ny };
    renderDot();
    setDirty();
    return;
  }
  // 其它模式：单击图片 = 在「自适应缩放 ↔ 原始分辨率」之间切换（自适应整幅可见，不足缩小、充足放大到占满可用区）
  if(!naturalW){ toast("图片尚未加载完成", "err"); return; }
  toggleMainMode();
});

/* ---------------- 保存 / 清除 ---------------- */
function collect(){
  const state = $("stateInput").value.trim();
  if(!labelOk(state)){
    toast("分类标注不能包含 \\ / : * ? \" < > | 等文件名字符，也不能以 . 结尾", "err");
    return null;
  }
  const body = { state, action: actionSel };
  if(actionSel === "click"){
    if(px){
      body.left = px.x; body.top = px.y;
    }else{
      const def = defOf(state);
      // 该分类已有统一定义（click）→ 不必逐张点坐标，后端直接采用分类定义坐标；
      // 尚未定义 → 必须点选（首次保存时该点会固定为该分类的标准坐标）
      if(!def || def.action !== "click"){
        toast("请先在图片上点选点击坐标（首次使用该分类时，该坐标会固定为该分类的标准动作坐标）", "err");
        return null;
      }
    }
  }
  return body;
}

function setDirty(){ dirty = true; }

/* 标注编辑面板可用性：列表为空 / 全部标记完成 → 右侧按钮与输入置灰，避免空操作 */
function setEditorEnabled(on){
  ["btnSaveNext","btnLast","btnClear","btnDelete","stateInput"].forEach(id => {
    const el = $(id); if(el) el.disabled = !on;
  });
  document.querySelectorAll('#edNorm input[name="action"]').forEach(r => { r.disabled = !on; });
}

/* 保存后取当前列表视觉顺序的下一张（全部 / 已标注 = 最新在上；未标注 = 最旧在上）；
   未标注视图下走到末尾 = 全部标记完 → 清空画面并把右侧编辑按钮置灰 */
function advanceAfterSave(item){
  const L = listNow();
  const p = L.findIndex(i => i.name === item.name);
  // p>=0 直接下一行；p<0 仅发生在未标注视图（保存后该图已移出列表，列表仍为升序）→ 按名称找更晚的下一张
  const np = p >= 0 ? p + 1 : L.findIndex(i => i.name > item.name);
  if(np >= 0 && np < L.length){
    selectTarget(L[np].name);
    const li = $("imgList").children[np]; if(li) li.scrollIntoView({block:"nearest"});
    return;
  }
  if(FILTER === "unmarked"){        // 未标注列表已空 → 全部完成
    renderList();
    showEmpty("🎉 所有截图都已标记完毕。可切换到「已标注」复核，或到「汇总分析」生成对照图。");
    toast("所有截图都已标记完毕", "ok");
  }else{
    toast("已是本列表最后一张", "info");
  }
}

async function saveCurrent(goNext){
  const item = cur(); if(!item) return;
  const body = collect(); if(!body) return;
  // 在“已标注该分类的图”上修改自己的动作/坐标 → 视为重定义该分类（动作坐标全组同步，保存前确认）；
  // 其余情况走唯一性校验（以中心表定义为准）。
  const redefine = !!(item.marked && item.state && item.state === body.state);
  if(redefine){
    const def = defOf(body.state);
    const oldAct = (def && def.action) || item.action || "none";
    const oldPx = (def && def.left != null && def.top != null)
      ? (def.left + "," + def.top)
      : (item.action === "click" && item.left != null ? (item.left + "," + item.top) : null);
    const newIsClick = body.action === "click";
    const sameAct = oldAct === (body.action || "none");
    const pxChanged = !!(newIsClick && body.left != null && oldPx !== (body.left + "," + body.top));
    if(!sameAct || pxChanged){
      if(newIsClick && body.left == null){
        toast("重定义「" + body.state + "」为鼠标点击：请先在图上点选新的点击坐标", "err");
        updateHints();
        return;
      }
      const dOld = oldAct === "click" ? "鼠标点击(" + oldPx + ")" : "无动作";
      const dNew = newIsClick ? "鼠标点击(" + (body.left + "," + body.top) + ")" : "无动作";
      if(!confirm("「" + body.state + "」的分类定义当前为「" + dOld + "」。\n本次保存将把它重新定义为「" + dNew + "」，并作为该分类全部样本的统一动作/坐标。\n\n继续？")) return;
    }else{
      // 已标注图原样重存（动作坐标都没变）→ 不触发“无坐标重定义”，也无需写盘
      toast("「" + body.state + "」未做改动，无需保存", "info");
      return;
    }
  }else{
    const conflict = actionConflict(body.state, body.action, item.name, false);
    if(conflict){ toast(conflict, "err"); updateHints(); return; }
  }
  try{
    const resp = await fetch(markUrl(item.name), {
      method:"PUT", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body)
    });
    if(!resp.ok){ toast("保存失败：" + (await resp.text() || resp.status), "err"); return; }
    const saved = await resp.json();
    item.marked = true; item.state = saved.state || ""; item.action = saved.action || "none";
    item.left = saved.left ?? null; item.top = saved.top ?? null;
    DEF[item.state] = { action: item.action || "none", left: item.left, top: item.top };   // 定义可能被后端采用/重定义，以响应为准
    lastMark = { state: saved.state || "", action: saved.action || "none", left: saved.left ?? null, top: saved.top ?? null };
    dirty = false;
    rebuildStates();
    renderList();
    refreshSmartTip();          // 当前图已变为已标注 → 隐藏智能分析提示条
    updateHints();
    toast("已保存 → " + SHORT(item.name) + ".json", "ok");
    await refreshSilent();          // 保存后立即同步：列表带上最新标记与新增截图
    if(goNext) advanceAfterSave(item);   // 按时间顺序取当前列表的下一张
    maybeAutoReload();                       // 若此前检测到服务端更新且已挂起，现在刷新
  }catch(e){ toast("保存失败：" + e.message, "err"); }
}

async function clearCurrent(){
  const item = cur(); if(!item) return;
  if(!confirm("清除 " + SHORT(item.name) + " 的标记？")) return;
  const pos = curIndex();        // 清除前在当前筛选列表中的位置，供“已标注”筛选下顺延用
  try{
    const resp = await fetch(markUrl(item.name), { method:"DELETE" });
    if(!resp.ok) throw new Error("HTTP " + resp.status);
    // 后端已把该图从 classify/ 移回 capture/（位置还原为“未标注”），此处同步视图状态
    item.marked = false; item.state=null; item.action=null; item.left=null; item.top=null;
    dirty = false;
    $("stateInput").value=""; setAction("none", false); px=null; renderDot();
    rebuildStates();            // 同步 chip 计数（该标签使用数 -1）
    toast("已清除标记", "ok");
    // 清除后仍属于当前筛选（全部 / 未标注）→ 停在原图刷新并给出智能建议；
    // 已不属于（如“已标注”筛选）→ 顺延到同位置下一张，列表空了回到空态。
    if(listNow().some(i => i.name === item.name)){
      renderList();
      refreshSmartTip();        // 当前图变回未标注 → 重新发起智能分析建议
    }else{
      const L = listNow();
      if(!L.length){ renderList(); showEmpty(); }
      else{ selectTarget(L[Math.max(0, Math.min(pos, L.length - 1))].name); }
    }
    await refreshSilent();          // 同步列表（新增截图与其它变化）
    maybeAutoReload();              // 若此前检测到服务端更新且已挂起，现在刷新
  }catch(e){ toast("清除失败：" + e.message, "err"); }
}

/* 删除当前图片（PNG + 同名标注）到系统回收站，无需确认，删除后自动顺延到下一张并用 toast 提示 */
async function deleteCurrent(){
  const item = cur(); if(!item){ toast("没有可操作的图片", "err"); return; }
  const pos = listNow().findIndex(i => i.name === item.name);
  try{
    const r = await fetch("/api/annotate/delete", {
      method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({ name:item.name })
    });
    if(!r.ok){ let m = "HTTP " + r.status; try{ m = (await r.text()) || m; }catch(_){} throw new Error(m); }
  }catch(e){ toast("删除失败：" + e.message, "err"); return; }
  const ai = ALL.indexOf(item); if(ai >= 0) ALL.splice(ai, 1);
  dirty = false; px = null;
  $("stateInput").value = ""; setAction("none", false); renderDot();
  rebuildStates();
  const L = listNow();
  toast("已移入回收站：" + SHORT(item.name) + (item.marked ? "（含标注）" : ""), "ok");
  if(!L.length){
    renderList();
    showEmpty();
  }else{
    selectTarget(L[Math.max(0, Math.min(pos, L.length - 1))].name);
  }
  await refreshSilent();   // 与后端同步（新截图等）
  maybeAutoReload();       // 若此前检测到服务端更新且已挂起，现在刷新
}

/* 把“上次的标记”应用到当前编辑区（无论该图是否已标注） */
function useLastMark(){
  const item = cur(); if(!item){ toast("没有可操作的图片", "err"); return; }
  if(!lastMark){ toast("还没有可用的上次标记——先输入并保存一张", "err"); return; }
  applyBodyToEditor(lastMark, true);
  toast("已带入上次的标记，可修改后保存");
}

/* ---------------- 导航 ---------------- */
function navStep(d){
  if(FILTER === "think"){ navThinkStep(d); return; }
  const L = listNow();
  if(!L.length){ toast("列表为空"); return; }
  if(dirty && !confirm("当前标注尚未保存，确定放弃修改？")) return;
  let p = L.findIndex(i=>i.name===curName);
  if(p<0){ p = d>0 ? -1 : L.length; }   // 当前图不在此筛选里（如刚保存完），从对应一端开始
  const np = p + d;
  if(np<0 || np>=L.length){ toast(d<0 ? "已是第一张" : "已是最后一张"); return; }
  curName = L[np].name;
  renderList();
  showImage(L[np]);
  const li = $("imgList").children[np]; if(li) li.scrollIntoView({block:"nearest"});
}

/* 汇总分析模式下 ↑/↓ 在组合队列里移动 */
function navThinkStep(d){
  const L = GROUPS;
  if(!L.length){ toast("没有可浏览的分类标注"); return; }
  let p = L.findIndex(g => gkey(g) === selKey);
  if(p<0){ p = d>0 ? -1 : L.length; }
  const np = p + d;
  if(np<0 || np>=L.length){ toast(d<0 ? "已是第一组" : "已是最后一组"); return; }
  openGroup(L[np]);
  const li = $("imgList").children[np]; if(li) li.scrollIntoView({block:"nearest"});
}

/* 顶部上一张/下一张/刷新等按钮已移除（顶栏右上角保留「开启/暂停截图」与「完全退出」），保留空实现兼容既有调用点 */
function updateNavButtons(){}

/* ---------------- 开启/暂停截图（截图默认不开启，需在页面手动开启） ---------------- */
let capPaused = true;    // 截图任务是否未开启/已暂停（程序启动后默认关闭）
let capBusy = false;
let capIntervalMs = null;            // 后端真实截图间隔（毫秒），由 /api/capture/status 下发
let capDiffThreshold = null;         // 后端像素去重阈值（%），0 = 关闭去重
let lastStopReasonShown = null;   // 已弹窗提示过的自动暂停原因（去重，避免每 5s 轮询重复弹窗）
let lastDedupNoticeAt = -1;       // 已提示过的「启动重复清理结果」时间戳（去重：一条结果只在首次轮询到的那一刻右下角提示，每 2s 轮询不重复打扰）

/* 毫秒间隔 → 人类可读文案（整千显示整秒，否则保留一位小数秒） */
function fmtCapInterval(ms){
  if(!ms || ms <= 0) return null;
  if(ms < 1000) return ms + " 毫秒";
  return (ms % 1000 === 0) ? (ms / 1000) + " 秒" : (ms / 1000).toFixed(1) + " 秒";
}

function renderCapBtn(){
  const b = $("btnCap"); if(!b) return;
  b.classList.toggle("on", !capPaused);        // 截图运行时按钮高亮为绿色
  b.textContent = capPaused ? "开启截图" : "暂停截图";
  b.disabled = capBusy;
  const per = fmtCapInterval(capIntervalMs);
  b.title = capPaused
    ? "截图未开启：点击后开始后台周期截图（原始截图保存到 capture/）"
    : "截图运行中（每 " + (per || "按配置间隔") + " 截取一帧" +
      (capDiffThreshold > 0 ? "，与每张已保存画面差异均 ≥ " + capDiffThreshold + "% 才保存" : "") +
      "）：点击暂停（不再截图保存，控制台其余功能不受影响）";
}

/* 进入页面先向后端取一次真实状态（可能别的页面/进程已改过） */
async function syncCapStatus(){
  try{
    const r = await fetch("/api/capture/status", { cache:"no-store" });
    if(!r.ok) return;
    const j = await r.json();
    capPaused = !!j.paused;
    if(j.intervalMs) capIntervalMs = j.intervalMs;
    if(j.diffThresholdPercent !== undefined) capDiffThreshold = j.diffThresholdPercent;
  }catch(e){ /* 服务未就绪时保持默认关闭态 */ }
  renderCapBtn();
}

async function toggleCap(){
  if(capBusy) return;
  const goPause = !capPaused;
  capBusy = true; renderCapBtn();
  try{
    const r = await fetch("/api/capture/" + (goPause ? "pause" : "resume"), { method:"POST", cache:"no-store" });
    if(!r.ok) throw new Error("HTTP " + r.status);
    const j = await r.json();
    capPaused = !!j.paused;
    if(!goPause) lastStopReasonShown = null;   // 重新开启成功：允许下次 resize 持续失败再次弹窗
    let msg;
    if(goPause){
      msg = "已暂停截图：后台不再截取保存新图，控制台仍可正常使用";
    }else{
      const per = fmtCapInterval(capIntervalMs);
      if(capDiffThreshold > 0){
        msg = "已开启截图：每 " + (per || "按配置间隔") + " 截取一帧，" +
              "画面与每张已保存图差异均 ≥ " + capDiffThreshold + "% 才会保存为新图";
      }else{
        msg = "已开启截图：每 " + (per || "按配置间隔") + " 截取一帧并保存";
      }
    }
    toast(msg, "ok");
  }catch(e){
    toast("切换失败：" + e.message, "err");
  }
  capBusy = false; renderCapBtn();
}

/* 后端自动暂停事件（截图 resize 持续无法达标）：弹窗错误提示，同步按钮态为「开启截图」 */
function showCapStopModal(msg){
  if(document.getElementById("capStopModal")) return;
  pushLog("截图已自动暂停：\n" + msg, "err");   // 自动暂停等关键事件一并写入历史日志（便于回溯原因）
  const ov = document.createElement("div");
  ov.id = "capStopModal";
  ov.className = "modal-ov";
  ov.innerHTML =
    '<div class="xcard">' +
      '<div class="xt2">截图已自动暂停</div>' +
      '<div class="msg"></div>' +
      '<div class="xrow">' +
        '<button class="btn green" id="capStopOK" type="button">知道了</button>' +
        '<button class="btn" id="capStopRetry" type="button">重新开启截图</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(ov);
  ov.querySelector(".msg").textContent = msg;   // textContent：后端原因含窗口标题等，避免注入
  const close = ()=>{ ov.remove(); };
  $("capStopOK").addEventListener("click", close);
  $("capStopRetry").addEventListener("click", async ()=>{
    ov.remove();
    await toggleCap();    // 此时任务已自动暂停 → 点击即 resume
  });
}

/* ---------------- 退出程序：确认 → 停后端 → 校验“真的停止”后才自关本页 ---------------- */
let exiting = false;    // 是否已从本页发起退出
let closeArmed = false; // 自动关闭本页流程是否已启动

const sleepMs = ms => new Promise(r => setTimeout(r, ms));

/* 自绘确认框：不依赖浏览器 confirm，避免内嵌/应用窗口里原生弹窗被拦截导致点击“无效” */
function askExitConfirm(){
  return new Promise(resolve => {
    if(document.getElementById("exitConfirm")) return resolve(false);
    const ov = document.createElement("div");
    ov.id = "exitConfirm";
    ov.innerHTML =
      '<div class="xcard">' +
        '<div class="xt2">退出程序</div>' +
        '<div class="msg">将停止控制台服务与后台截图（录屏）任务，并自动关闭本页。确定退出？</div>' +
        '<div class="xrow">' +
          '<button class="btn" id="exitCancel" type="button">取消</button>' +
          '<button class="btn danger" id="exitOK" type="button">确定退出</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(ov);
    const done = v => { ov.remove(); resolve(v); };
    $("exitCancel").addEventListener("click", ()=> done(false));
    $("exitOK").addEventListener("click", ()=> done(true));
  });
}

/* 探测后端是否仍存活（连接被拒/无响应视为已停止） */
function probeAlive(){
  return fetch("/api/app/meta", { cache:"no-store" }).then(()=> true).catch(()=> false);
}

/* 移除退出相关全屏覆盖（正在退出 / 已退出 / 退出失败），保证各阶段界面正确切换 */
function clearExitScreens(){
  const a = document.getElementById("exitScreen");    if(a) a.remove();
  const b = document.getElementById("exitingScreen"); if(b) b.remove();
}

/* 停服进行中：立即整屏切换到“正在退出”画面（风格与“已退出”一致），用户不再能操作界面 */
function showExitingScreen(){
  clearExitScreens();
  const ov = document.createElement("div");
  ov.id = "exitingScreen";
  ov.innerHTML =
    '<div class="xt" style="color:var(--amber)">正在退出程序…</div>' +
    '<div class="msg">正在停止控制台服务与后台截图（录屏）任务。<br>此过程约需数秒，完成后页面会自动关闭。</div>';
  document.body.appendChild(ov);
}

/* 后端确认已停止：覆盖层提示 + 兜底按钮 */
function showExitScreen(){
  clearExitScreens();
  const ov = document.createElement("div");
  ov.id = "exitScreen";
  ov.innerHTML =
    '<div class="xt">已退出</div>' +
    '<div class="msg">程序已停止，本页将自动关闭。<br>若浏览器禁止脚本自动关页，请点击下方按钮手动关闭。</div>' +
    '<button class="btn danger" id="exitClose" type="button">立即关闭本页</button>';
  document.body.appendChild(ov);
  $("exitClose").addEventListener("click", ()=>{ try{ window.close(); }catch(e){ /* 忽略 */ } });
}

/* 多次尝试后后端仍存活：如实提示并提供重试 */
function showExitFailed(msg){
  clearExitScreens();
  const ov = document.createElement("div");
  ov.id = "exitScreen";
  ov.innerHTML =
    '<div class="xt" style="color:var(--danger)">未能停止程序</div>' +
    '<div class="msg">' + msg + '</div>' +
    '<div class="xrow">' +
      '<button class="btn danger" id="exitRetry" type="button">再试一次</button>' +
      '<button class="btn" id="exitKeep" type="button">保留页面手动处理</button>' +
    '</div>';
  document.body.appendChild(ov);
  $("exitRetry").addEventListener("click", ()=>{ ov.remove(); requestExit(); });
  $("exitKeep").addEventListener("click", ()=>{ ov.remove(); });
}

async function requestExit(){
  if(exiting) return;
  if(!await askExitConfirm()) return;
  startExit();
}

/* 停服主流程：从“确定退出”之后开始执行（页面右上角「完全退出」与关闭窗口时“退出整个程序”共用） */
async function startExit(){
  if(exiting) return;
  exiting = true;
  const btn = $("btnExit");
  btn.disabled = true; btn.textContent = "正在退出…";
  showExitingScreen();                  // 立即整屏切到“正在退出”画面，风格同“已退出”，期间用户不可再操作
  const deadline = Date.now() + 10000;   // 等待后端停止的最长时间
  let deadCount = 0;                     // 连续探测“不可达”次数（>=2 才判定退出，防网络抖动误判）
  while(Date.now() < deadline){
    try{
      await fetch("/api/system/shutdown", { method:"POST", keepalive:true, cache:"no-store" });
    }catch(e){ /* 服务端可能已先行关闭，交由下方探测判定 */ }
    await sleepMs(500);
    const alive = await probeAlive();
    if(!alive){
      if(++deadCount >= 2){
        showExitScreen();               // 后端确实已停：从“正在退出”切到“已退出”，随后自动关页
        armPageClose(1600);             // 延迟约 1.6s 再尝试自动关页，确保提示可见（失败时留手动按钮）
        return;
      }
    } else {
      deadCount = 0;
    }
  }
  exiting = false;
  btn.disabled = false; btn.textContent = "完全退出";
  showExitFailed("服务进程未能在 10 秒内停止。可再试一次；若持续失败，请在任务管理器中手动结束 java 进程。");
}

/* 反复尝试自动关闭本页：delayMs 后再开始尝试，让“已退出”覆盖层提示先可见；
   应用窗口（Edge/Chrome --app）模式下脚本可自关；普通标签页约 6 秒后放弃，留给手动按钮兜底 */
function armPageClose(delayMs){
  if(closeArmed) return;
  closeArmed = true;
  setTimeout(()=>{
    let n = 0;
    const timer = setInterval(()=>{
      try{ window.close(); }catch(e){ /* 忽略 */ }
      if(++n >= 15){ clearInterval(timer); }
    }, 400);
  }, delayMs || 0);
}

/* ---------------- 关闭程序窗口（右上角 × / Alt+F4）：仅关前端 or 连同后台一起退出 ---------------- */
let allowReloadClose = false;  // 代码触发的页面刷新（如检测到服务端更新）直接放行，不弹关闭确认

/* Chromium 应用窗口点关闭时浏览器会先弹原生“离开”确认（应用页面无法绕过原生框）：
   - 选“离开” = 仅关闭前端窗口，后台服务与截图任务继续运行；
   - 选“取消” = 停留在页面，此时露出下方自绘选择框（覆盖层在事件触发时已先就位）。
   本页主动退出 / 服务已停自动关页 / 代码触发的刷新 都直接放行，不拦截。 */
window.addEventListener("beforeunload", e => {
  if(exiting || closeArmed || allowReloadClose) return;  // 放行
  e.preventDefault();
  e.returnValue = "";
  showXCloseAsk();
});

function showXCloseAsk(){
  if(document.getElementById("xCloseAsk")) return;
  const ov = document.createElement("div");
  ov.className = "modal-ov";
  ov.id = "xCloseAsk";
  const dirtyNote = dirty
    ? '<div style="background:rgba(255,90,90,.12);border:1px solid rgba(255,90,90,.4);color:#ffd2c9;padding:7px 10px;border-radius:8px;font-size:12px;margin-bottom:10px;text-align:left">当前标注尚未保存，关闭窗口会丢弃这些修改。</div>'
    : "";
  const pageUrl = location.origin + location.pathname;   // 后端服务地址即当前页面地址，用作“以后重开网页”的入口
  ov.innerHTML =
    '<div class="xcard" style="max-width:560px">' +
      '<button type="button" id="xCloseX" class="modalX" title="取消（继续使用）" aria-label="取消（继续使用）">✕</button>' +
      '<div class="xt2">关闭程序窗口</div>' +
      '<div class="msg" style="text-align:left">' + dirtyNote +
        '关闭<b>窗口</b>，默认只退出前端界面，后台服务会<b style="color:#9ad8a8">继续运行</b>。<br>' +
        '页面也可以用网址打开：<a href="' + pageUrl + '" target="_blank" rel="noopener" style="color:#7cc4ff;word-break:break-all">' + pageUrl + '</a>' +
      '</div>' +
      '<div class="xrow" style="flex-wrap:wrap">' +
        '<button class="btn danger" id="xExitAll" type="button">退出整个程序</button>' +
        '<button class="btn" id="xOnlyUI" type="button">仅关闭窗口</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(ov);
  $("xCloseX").addEventListener("click", ()=>{ ov.remove(); });   // 右上角 ✕ = 取消（继续使用）
  $("xOnlyUI").addEventListener("click", ()=>{
    ov.remove();
    toast("仅关闭前端窗口，后台服务继续运行；如需彻底退出请重开页面后点右上角「完全退出」。", "");
    try{ window.close(); }catch(e){ /* 普通标签页可能禁止脚本自关：上面的 toast 已说明，用户可手动关闭本页 */ }
  });
  $("xExitAll").addEventListener("click", ()=>{
    ov.remove();     // 已在本选择框确认过“退出整个程序”，直接执行停服，不再二次询问
    startExit();
  });
}

/* ---------------- 汇总分析 · 组合分析工作台 ---------------- */
const escHtml = s => String(s).replace(/[&<>"]/g, c => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;" }[c]));

let GROUPS = [];              // 后端组合总览（state×action）
let selKey = null;            // 当前选中组合 key = state\u0001action
let thinkBusy = false;        // 后台是否正在批量分析
let thinkTaskMsg = "";        // 批量任务进行中主图 dock 的进行态文案（切换分组时仍保持显示）
let lastThinkSig = "";        // 组合列表签名（避免无变化时反复刷新闪烁）

const gkey = g => g.state + "\u0001" + g.action;
const b64u = s => btoa(unescape(encodeURIComponent(s)));   // UTF-8 → Base64（ASCII 安全传目录名）
// Base64 可能含 + / =，直接放查询串会被服务端按 URL 解码成空格等非法字符（导致图片 404/400 裂图），必须再转义一层
const artUrl = (kind, dir, v) => "/api/annotate/think/img/" + encodeURIComponent(kind) + "?dir=" + encodeURIComponent(b64u(dir || ""))
    + (v ? "&v=" + v : "");   // v = 产物 info.json 的 mtime：后台自动重算后 URL 变化 → 浏览器绕过 1h 缓存拉到新对照图
const fmtCov = c => (c == null ? "—" : Number(c) + "%");

function thinkSig(){
  return GROUPS.map(g => [g.state,g.action,g.sampleCount,!!g.analyzed,!!g.stale,g.coverage,g.dir,g.mtime||0].join("|")).join("\n");
}
/* 汇总分析 · 处理过程状态条：渲染到主图底部浮层 dock（thinkBar，与「未标注」的智能分析
   提示条同处同构、悬浮在主图上不占文档流）。切换分组 / 分析进度变化时实时更新。
   待生成（≥1 张即自动分析） → 正在生成 → 样本有变 → 已生成，均只在此展示一次 */
function renderThinkDock(g){
  const b = $("thinkBar");
  if(FILTER === "think" && thinkTaskMsg){       // 批量任务进行中：不渲染（可能已过时的）分组状态，保持任务进行态
    b.hidden = false;
    b.className = "thinkbar warn";
    b.innerHTML = '<span class="tb-title">汇总分析</span><span>' + escHtml(thinkTaskMsg) + '</span>';
    syncDockNow();
    return;
  }
  if(!g){ b.hidden = true; syncDockNow(); return; }
  let cls = "", text;
  if(!g.analyzed){
    if(g.canAnalyze){
      cls = "warn";
      text = '「<b>' + escHtml(g.state) + '</b>」样本已达 <b>' + g.sampleCount
        + ' 张</b>，正在后台合成对照图，完成后自动显示在上方。';
    }else{
      cls = "bad";
      text = '该分类还没有可用的标注截图，无法合成对照图；标注 1 张后会自动生成。';
    }
  }else if(g.stale){
    cls = "warn";
    text = '样本有变：新增 / 改动样本后对照图尚未重算，稍后会自动更新。';
  }else{
    const tail = g.hasUnique
      ? '14 张对照图（7 张基础图 + 7 张独有区图）已生成。'
      : '对照图已生成；各独有区图（本分类独有区域）将在随后的后台重算中补齐。';
    text = g.coverage != null
      ? '交集图像素覆盖率<b class="kv">' + fmtCov(g.coverage) + '</b>，' + tail
      : tail;
  }
  b.hidden = false;
  b.className = "thinkbar" + (cls ? " " + cls : "");
  b.innerHTML = '<span class="tb-title">汇总分析</span><span>' + text + '</span>';
  syncDockNow();
}
/* 后台批量任务进行中（自动补分析 / 全量重建）：主图下方 dock 不再停留旧的“已生成”摘要，
   改为一行任务进行态并随轮询刷新进度；任务结束（成功 / 失败）后由 thinkTaskDone
   重新按最新组合状态渲染（恢复“已生成 / 覆盖率 / 正在后台合成”等正确状态） */
function thinkBusyDock(text){
  const b = $("thinkBar");
  if(text == null){                              // 任务结束：清空进行态，恢复分组状态渲染
    thinkTaskMsg = "";
    if(FILTER !== "think"){ b.hidden = true; syncDockNow(); return; }
    const g = selKey ? (GROUPS.find(x => gkey(x) === selKey) || null) : null;
    renderThinkDock(g);
    return;
  }
  if(FILTER !== "think"){ b.hidden = true; syncDockNow(); return; }   // 已退出汇总分析：不残留浮层
  thinkTaskMsg = text;
  b.hidden = false;
  b.className = "thinkbar warn";
  b.innerHTML = '<span class="tb-title">汇总分析</span><span>' + escHtml(text) + '</span>';
  syncDockNow();
}
/* 批量任务收尾：复位 busy 标志与进行态文案 → 重新拉取组合总览，让列表 / 主图 / dock 回到最新状态 */
async function thinkTaskDone(){
  thinkBusy = false;
  thinkTaskMsg = "";
  if(FILTER !== "think"){ $("thinkBar").hidden = true; syncDockNow(); return; }
  await refreshThink(false, false);
  renderThinkList();
  thinkBusyDock(null);   // dock 从“任务进行态”恢复为当前分组的最新状态（覆盖率 / 后台合成中…）
}
/* 主图区轻占位：无对照图时给一行极简提示；具体原因与下一步见底部浮层状态条 */
function showThinkEmpty(cls, title){
  const te = $("thinkEmpty");
  te.className = ["bad","warn"].includes(cls) ? cls : "";
  $("teTitle").textContent = title || "";
  te.style.display = "flex";
}
const THINK_EMPTY = "没有分类标注";
/* 汇总分析对照图（顺序：每张基础图与其 -unique 独有区图成对出现：
   same/same-unique → max/max-unique → avg/avg-unique → maj8/maj8-unique → maj32/maj32-unique → avg8/avg8-unique → avg32/avg32-unique；
   14 张都参与执行模式比对：差异度 = (独有交集图×50 + 交集图×30 + 其余 12 张平均×20)/100；
   -unique 图须等全部分组的 7 张基础图都生成完后由后台统一补算，未生成前本组先不展示独有区图卡片） */
const IMG_IDS = ["imgSame","imgUnique","imgMax","imgMaxU","imgAvg","imgAvgU","imgM8","imgM8U","imgM32","imgM32U","imgA8","imgA8U","imgA32","imgA32U"];
const TCS_IDS = ["tcsSame","tcsUnique","tcsMax","tcsMaxU","tcsAvg","tcsAvgU","tcsM8","tcsM8U","tcsM32","tcsM32U","tcsA8","tcsA8U","tcsA32","tcsA32U"];

/* ---- 对照图加载容错：快速切换分组时，同一 <img> 连续换 src 会中止上一组在途请求，
   浏览器偶发把中止/瞬时失败残留成裂图（此时服务端文件其实完好——再点裂图在弹窗里能正常加载）。
   方案：openGroup 每轮递增 thinkSeq，只有「仍是最新分组 + src 仍是目标 URL」的失败才短延时自动重试，
   过期分组的失败一律忽略，切走即中断旧定时器。 ---- */
let thinkSeq = 0;                       // 分组切换序号
const thinkRetry = {};                  // imgId → { seq, url, tries, timer }
const thinkImgState = id => (thinkRetry[id] || (thinkRetry[id] = { seq:0, url:"", tries:0, timer:0 }));
/* 记录当前 img 正在加载的目标（赋 src 后调用），并清掉旧定时器 */
function armThinkRetry(el, url){
  const st = thinkImgState(el.id);
  st.seq = thinkSeq; st.url = url; st.tries = 0;
  if(st.timer){ clearTimeout(st.timer); st.timer = 0; }
}
/* 分组已切走/清空：让该 img 的一切在途重试失效 */
function clearThinkRetry(el){
  const st = thinkImgState(el.id);
  st.seq = -1;
  if(st.timer){ clearTimeout(st.timer); st.timer = 0; }
}
/* <img> error：若是当前展示目标则延时重试（间隔递增，最多 4 次，随后交给 10s 自动轮询兜底） */
function onThinkImgError(el){
  const st = thinkImgState(el.id);
  if(st.seq !== thinkSeq) return;                  // 过期分组（已切走）
  if(!el.getAttribute("src") || el.getAttribute("src") !== st.url) return;
  if(st.tries >= 4) return;
  st.tries++;
  st.timer = setTimeout(() => {
    st.timer = 0;
    if(st.seq === thinkSeq && FILTER === "think"
        && el.getAttribute("src") === st.url && $("imgwrap") && $("imgwrap").style.display === "none"){
      el.src = st.url;                            // 重新发起同一请求（产物当时可能正被后台重算，短暂重试即好）
    }
  }, 400 * st.tries);
}

/* 进入汇总分析工作台 */
function enterThink(){
  FILTER = "think";
  dirty = false;
  stateFilter = null;          // 汇总分析不沿用「全部」视图的分类过滤
  updateCountsOnly();          // 入口即同步顶部计数（异步落盘；分组列表由下面 refreshThink 全量刷新）
  syncRightPanel();            // 右栏切到「汇总分析」（隐藏标注编辑与分类过滤）
  $("imgwrap").style.display = "none";
  $("placeholder").style.display = "none";
  showZoomCtl(false);      // 汇总分析各对照图用各自的 lightbox 放大，隐藏主图缩放控件
  resetZoom();
  hideSmartTip();          // 汇总分析模式下不显示「未标注」智能分析建议条
  syncSugDock();           // 汇总分析：处理状态浮层按 thinkBar 内容自动显示
  $("lstTitle").textContent = "分类标注列表（按匹配度）";
  selKey = null;
  openGroup(null);
  refreshThink(true, false);
}

/* 退出回普通截图列表 */
function exitThink(){
  closeLightbox();
  $("statusTag").style.display = "none";  // 汇总分析里可能显示了徽标，回截图视图前隐藏（图片上方不再放状态徽标）
  $("thinkEmpty").style.display = "none"; // 隐藏主区占位，避免残留影响普通截图视图
  $("thinkEmpty").className = "";
  $("thinkBar").hidden = true;             // 清空汇总分析状态浮层，退出后该浮层交给智能分析提示条
  syncDockNow();
  thinkMeta = null;                        // 退出后清掉列表基准，避免普通视图误排版
  $("thinkPane").style.display = "none";
  $("edThink").style.display = "none";
  $("edNorm").style.display = "";
  $("lstTitle").textContent = "截图列表（按时间）";
  for(const id of IMG_IDS){
    const el = $(id);
    el.removeAttribute("src");
    const wrap = el.closest(".tcard");
    if(wrap) wrap.style.display = "";
  }
}

function renderThinkList(){
  setSegState();   // 筛选按钮仍显示全部/未标注/已标注的截图计数，选中态切到“汇总分析”
  $("listCount").textContent = GROUPS.length + " 个";   // 任务进度不再挤进列表头小角，改由右下角 taskTip 闪现提示
  const ul = $("imgList"); ul.innerHTML = "";
  for(const g of GROUPS){
    const li = document.createElement("li");
    li.className = "row" + (selKey === gkey(g) ? " on" : "");
    let extra = g.sampleCount + " 张";
    if(g.analyzed) extra += g.stale ? " · 待重分析" : " · 覆盖率 " + fmtCov(g.coverage);
    else extra += g.canAnalyze ? " · 自动分析中" : " · 暂无样本";
    li.innerHTML =
      '<div class="r1"><span class="t">' + escHtml(g.state) + '</span>' +
      '<span class="actx">' + (ACT_LABEL[g.action] || g.action) + '</span></div>' +
      '<div class="r2">' + extra.replace(/</g,"&lt;") + '</div>' +
      (g.analyzed && !g.stale && g.coverage != null
        ? '<div class="tbar"><i class="' + (Number(g.coverage) >= 100 ? "full" : "") + '" style="width:'
            + Math.max(0, Math.min(100, Number(g.coverage))) + '%"></i></div>'
        : '');
    li.addEventListener("click", ()=> openGroup(g));
    ul.appendChild(li);
  }
  if(!GROUPS.length){
    const d = document.createElement("li"); d.className = "empty";
    d.textContent = THINK_EMPTY;
    ul.appendChild(d);
  }
}

/* 异步取一次组合总览并刷新视图；autoAnalyze=true 时自动补分析尚未生成对照图的组合 */
async function refreshThink(autoAnalyze, silent){
  let arr = null;
  try{
    const r = await fetch("/api/annotate/think/groups", { cache:"no-store" });
    if(!r.ok) throw new Error("HTTP " + r.status);
    arr = await r.json();
  }catch(e){
    if(!silent) toast("加载分析状态失败：" + e.message, "err");
    return;
  }
  const prevKey = selKey;
  GROUPS = arr;
  const sig = thinkSig();
  const changed = sig !== lastThinkSig;
  lastThinkSig = sig;
  renderThinkList();
  if(selKey){
    const g = GROUPS.find(x => gkey(x) === prevKey);
    if(g){ if(changed) openGroup(g); }
    else { selKey = null; openGroup(null); }
  }
  if(!selKey) autoPick();
  if(autoAnalyze) startAnalyzeIfNeeded();
}

/* 自动选中一组：在当前子筛选可见的组合里优先挑已分析的 */
function autoPick(){
  if(!GROUPS.length){ openGroup(null); return; }
  openGroup(GROUPS.find(x => x.analyzed && !x.stale) || GROUPS[0]);
}

/* ---------------- 汇总分析 · 对照图“原图一半”列表排版 ----------------
   全幅对照图（交集/多数/均值及各自 -unique 独有区图）统一按“原图的一半”展示：原尺寸平滑缩到 1/2；
   1/8、1/32 压缩图按整数倍放大到与半尺寸接近（保留像素锐利），保证一行视觉整齐 */
let thinkMeta = null;       // { halfW, halfH } 当前组的列表基准尺寸

function cardTargetScale(nw, nh){
  if(!thinkMeta || !thinkMeta.halfW || !thinkMeta.halfH) return { s:1, pixel:false };
  const halfW = thinkMeta.halfW, halfH = thinkMeta.halfH;
  if(nw >= halfW && nh >= halfH) return { s: halfW / nw, pixel:false };   // 原图半缩放（平滑）
  let k = Math.max(1, Math.floor(halfW / nw));                            // 小图按整数倍放大
  k = Math.max(1, Math.min(k, Math.floor(halfH / nh)));
  return { s: k, pixel: k > 1 };
}

/* 图片 load 后套用列表基准尺寸 */
function applyCardLayout(img){
  if(!thinkMeta || FILTER !== "think") return;
  const nw = img.naturalWidth, nh = img.naturalHeight;
  if(!nw || !nh) return;
  const t = cardTargetScale(nw, nh);
  img.style.width = Math.max(1, Math.round(nw * t.s)) + "px";
  img.style.height = "auto";
  img.style.imageRendering = t.pixel ? "pixelated" : "auto";
}

/* 右栏底部的操作说明条：仅在对照图已生成时给弹窗缩放提示；未生成时隐藏
   （处理状态与原因统一由主图底部 dock 说明，此处不再重复） */
function setThinkExplain(t){
  const el = $("tkExplain");
  el.innerHTML = t;
  el.style.display = t ? "" : "none";
}

/* 在右侧/主区展示某组；g=null 清空。切换分组会同步刷新主图区与底部状态 dock，避免残留上一分组 */
function openGroup(g){
  closeLightbox();
  thinkSeq++;                       // 分组切换序号：上一组在途加载 / 失败重试整体作废
  selKey = g ? gkey(g) : null;
  renderThinkList();
  const imgs = IMG_IDS.map(id => $(id));
  const tp = $("thinkPane");
  for(const el of imgs){
    clearThinkRetry(el);
    el.removeAttribute("src");
    el.style.width = ""; el.style.height = "";
    el.style.imageRendering = "auto";
  }
  thinkMeta = null;
  renderThinkDock(g);                   // 处理状态固定展示在主图底部 dock：待生成 / 正在生成 / 样本有变 / 已生成
  const te = $("thinkEmpty");
  if(!g){
    tp.style.display = "none";
    te.style.display = "none";
    $("fname").textContent = "汇总分析";
    $("fsub").textContent = "";
    $("imgDims").textContent = "";
    $("tkInfo").innerHTML = "左侧选择分类标注查看对照图。";
    setThinkExplain("");
    return;
  }
  $("fname").textContent = g.state + " ｜ " + (ACT_LABEL[g.action] || g.action);
  $("fsub").textContent = g.sampleCount + " 张";
  $("imgDims").textContent = "";
  let info = '【分类标注】<b class="kv">' + escHtml(g.state) + '</b><br>'
           + '【匹配动作】<b class="kv">' + (ACT_LABEL[g.action] || g.action) + '</b><br>'
           + '【原始截图】<b class="kv">' + g.sampleCount + ' 张</b>';
  if(g.analyzed){
    tp.style.display = "grid";           // 恢复为 CSS 网格（对照图）
    te.style.display = "none";
    const sz8 = v => Math.max(1, Math.floor(v / 8));
    const sz32 = v => Math.max(1, Math.floor(v / 32));
    const W = Number(g.width) || 0, H = Number(g.height) || 0;
    if(W && H){
      thinkMeta = { halfW: Math.max(1, Math.round(W / 2)), halfH: Math.max(1, Math.round(H / 2)) };
      tp.style.gridTemplateColumns = "repeat(auto-fit, " + (thinkMeta.halfW + 20) + "px)";
    } else {
      tp.style.gridTemplateColumns = "repeat(auto-fit, 700px)";
    }
    // 独有区图覆盖率（kind → 独有像素占该图全图百分比；未生成时为 —）
    const uc = kind => fmtCov(g.uniqueCov ? g.uniqueCov[kind] : null);
    const kinds = [["same","imgSame","tcsSame",fmtCov(g.coverage)],
                   ["same-unique","imgUnique","tcsUnique", uc("same-unique")],
                   ["max","imgMax","tcsMax", W + "×" + H],
                   ["max-unique","imgMaxU","tcsMaxU", uc("max-unique")],
                   ["avg","imgAvg","tcsAvg", W + "×" + H],
                   ["avg-unique","imgAvgU","tcsAvgU", uc("avg-unique")],
                   ["m8","imgM8","tcsM8", sz8(W) + "×" + sz8(H) + " · 多数"],
                   ["m8-unique","imgM8U","tcsM8U", uc("m8-unique")],
                   ["m32","imgM32","tcsM32", sz32(W) + "×" + sz32(H) + " · 多数"],
                   ["m32-unique","imgM32U","tcsM32U", uc("m32-unique")],
                   ["a8","imgA8","tcsA8", sz8(W) + "×" + sz8(H) + " · 均值"],
                   ["a8-unique","imgA8U","tcsA8U", uc("a8-unique")],
                   ["a32","imgA32","tcsA32", sz32(W) + "×" + sz32(H) + " · 均值"],
                   ["a32-unique","imgA32U","tcsA32U", uc("a32-unique")]];
    for(const [kind,imgId,tcsId,label] of kinds){
      const el = $(imgId), card = el.closest(".tcard");
      if(kind.endsWith("-unique") && !g.hasUnique){
        // -unique 独有区图需跨分类对比：本组尚未经「全部分组基础图齐备后的那次重算」生成 → 先不展示
        // （随后台自动重算补齐，或对该组重新分析）
        $(tcsId).textContent = "";
        card.style.display = "none";
        continue;
      }
      const url = artUrl(kind, g.dir, g.mtime);
      el.src = url;
      armThinkRetry(el, url);                                   // 记录本次加载目标：瞬时失败可按需自动重试
      if(el.complete && el.naturalWidth) applyCardLayout(el);   // 命中缓存时立即排版
      card.style.display = "";
      $(tcsId).textContent = label;
    }
    if(W && H){
      info += '<br>【分辨率】<b class="kv">' + W + '×' + H + '</b>';
    }
  } else {
    tp.style.display = "none";           // 无对照图 → 主区用占位提示替代空白网格
    for(const id of IMG_IDS){
      $(id).closest(".tcard").style.display = "none";
    }
    // 有 1 张样本即可分析，无对照图只是后台尚未合成完成：只给一行极简空态词；
    // 原因与下一步统一由底部 dock（renderThinkDock）说明，避免重复文案
    showThinkEmpty(g.canAnalyze ? "warn" : "bad", g.canAnalyze ? "正在合成对照图…" : "暂无对照图");
  }
  $("tkInfo").innerHTML = info;
  setThinkExplain(g.analyzed
    ? "单击弹窗内图片在「自适应缩放 ↔ 原始分辨率」间切换，点空白或按 Esc 关闭。"
    : "");
}

/* 自动分析所有「可分析但尚未生成对照图」的组合 */
async function startAnalyzeIfNeeded(){
  if(thinkBusy) return;
  const need = GROUPS.filter(g => g.canAnalyze && (!g.analyzed || g.stale === true));
  if(!need.length){ renderThinkList(); return; }
  thinkBusy = true; renderThinkList();
  thinkBusyDock("正在后台分析，为「自动分析中」的组合合成对照图…");
  toast("发现 " + need.length + " 个分类标注待生成对照图，开始后台分析…", "");
  try{
    const r = await fetch("/api/annotate/think/analyze", {
      method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ force:false })
    });
    if(!r.ok){ let m="HTTP "+r.status; try{ const j=await r.json(); if(j&&j.error)m=j.error; }catch(_){} throw new Error(m); }
    const j = await r.json();
    await pollAnalyze(j.taskId, "自动分析");
  }catch(e){
    toast("启动分析失败：" + e.message, "err");
  }
  await thinkTaskDone();
}

async function pollAnalyze(id, label){
  label = label || "分析";
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  for(let i=0;i<3600;i++){
    await sleep(700);
    let t = null;
    try{
      const r = await fetch("/api/annotate/think/task/" + encodeURIComponent(id), { cache:"no-store" });
      if(!r.ok) throw new Error();
      t = await r.json();
    }catch(_){ continue; }     // 服务短暂中断则等下一轮
    if(t.status === "running"){
      // 后台为单线程串行计算池：任务刚提交 / 排在自动重算等其它分析后面时，total 尚未统计出来（0/0），
      // 此时只报“正在…”，避免把无意义的 0/0 当作卡死；total 确定后显示真实进度 processed/N。
      // 任务分 2 轮：第 1 轮逐分类生成 7 张基础对照图（processed/total 计数），第 2 轮生成各分类
      // 7 张 -unique 独有区图（跨分类按 kind 推进，current 指示当前图种）。
      // 进度不挤在列表头小角，改为：图片下方 dock 实时刷新进行态 + 右下角 taskTip 单条闪现（文本变化入库）
      const hasN = t.total > 0;
      const stage = t.stage === 2 ? 2 : 1;
      const round = stage === 2 ? "第 2 轮 · 独有区图" : "第 1 轮 · 基础对照图";
      const prog = stage === 2
        ? (t.current ? " · " + t.current : "")
        : (hasN ? " " + t.processed + "/" + t.total + (t.current ? "（" + t.current + "）" : "") : "");
      thinkBusyDock("正在" + label + " · " + round + prog);
      taskTip("正在" + round + prog);
      continue;
    }
    if(t.status === "error"){ taskTip(null); toast(t.message || "分析失败", "err"); return; }
    taskTip(null);
    toast(t.message || "分析完成", "ok");
    return;
  }
  taskTip(null);
  toast("分析耗时过长，已停止等待（可稍后重新进入本栏）", "err");
}

/* 「重新生成全部对照图」：先清空 summary/ 全部产物，再全量重建。删除在后台计算线程内串行执行，
   不会与自动重算/其它分析互踩；产物由 classify/ 已标注样本派生，删除不影响原始截图与标注 */
async function rebuildThink(){
  if(thinkBusy){ toast("已有分析任务进行中，请稍候", "warn"); return; }
  if(!confirm("将清空 summary/ 下全部对照图产物，并从 classify/ 已标注样本重新生成每个分类的 14 张对照图。\n原始截图与标注不受影响。\n\n确定继续？")) return;
  thinkBusy = true; renderThinkList();
  thinkBusyDock("正在全量重建全部对照图…（将先清空 summary/ 旧产物）");
  try{
    const r = await fetch("/api/annotate/think/rebuild", { method:"POST" });
    if(!r.ok){ let m="HTTP "+r.status; try{ const j=await r.json(); if(j&&j.error)m=j.error; }catch(_){} throw new Error(m); }
    const j = await r.json();
    toast("已清空产物，开始全量重建…", "");
    await pollAnalyze(j.taskId, "全量重建");
  }catch(e){
    toast("启动重建失败：" + e.message, "err");
  }
  await thinkTaskDone();
}

/* 80% 视口弹窗：默认「自适应缩放」（整幅可见并尽量占满：不足等比缩小、空间充足等比放大到框内最大，四周留少量空白，不产生滚动条）；
   单击弹窗内图片在「自适应缩放 ↔ 原始分辨率」间切换；点空白 / Esc / ✕ 关闭 */
let lbMode = "fit";
let lbNatural = { w:0, h:0 };

function openLightbox(src, cap){
  const lb = $("imgLightbox"), img = $("lbImg"), capEl = $("lbCap");
  lbMode = "fit";
  lbNatural = { w:0, h:0 };
  img.style.width = ""; img.style.height = "";
  img.style.imageRendering = "auto";
  img.src = src;
  capEl.textContent = cap + "　·　单击图片：自适应缩放 ↔ 原始分辨率；点空白 / Esc 关闭";
  lb.classList.add("show");
}
function closeLightbox(){
  $("imgLightbox").classList.remove("show");
  $("lbImg").removeAttribute("src");
}
/* 弹窗可用区 ≈ 视口 80% */
/* 弹窗可用区 ≈ 视口 80%（再预留 FIT_GAP_PX：lbBox 自身有 1px 边框且按 border-box 限 80vw，
   直接算到满会因边框宽度超界而触发滚动条，内缩后可避免） */
function lbBox(){
  return {
    w: Math.max(100, window.innerWidth * 0.8 - FIT_GAP_PX),
    h: Math.max(100, window.innerHeight * 0.8 - 72 - FIT_GAP_PX)
  };
}
function lbScale(){
  const nw = lbNatural.w, nh = lbNatural.h;
  if(!nw || !nh) return 0;
  if(lbMode === "orig") return 1;                                   // 原始分辨率：1:1 原像素
  const B = lbBox();
  // 自适应缩放 = 整幅可见且尽量占满弹窗：空间不足等比缩小、空间充足等比放大到可放下的最大尺寸；
  // floor 后不超出弹窗，四周留少量空白，始终不产生滚动条
  return Math.min(B.w / nw, B.h / nh);
}
function applyLbZoom(){
  const img = $("lbImg");
  const s = lbScale();
  if(!s) return;
  img.style.width = Math.max(1, Math.floor(lbNatural.w * s)) + "px";  // floor：保证不超出，不产生滚动条
  img.style.height = Math.max(1, Math.floor(lbNatural.h * s)) + "px";
  img.style.imageRendering = "auto";   // 平滑缩放（缩小或放大）、原始分辨率 1:1，无需像素锐化
  const box = img.parentElement;
  box.scrollLeft = 0; box.scrollTop = 0;
}
function toggleLbMode(){
  lbMode = lbMode === "orig" ? "fit" : "orig";
  applyLbZoom();
}

/* ---------------- 智能分析：未标注图 × 执行模式同一匹配口径（14 张对照图差异度） ---------------- */
let sugSeq = 0;          // 目标切换序号：用于丢弃过期轮询
let sugTimer = 0;        // 建议任务轮询定时器
let sugDismiss = new Set();  // 用户手动收起的智能分析（按文件名），对应图不再自动弹出

function sugStop(){ if(sugTimer){ clearTimeout(sugTimer); sugTimer = 0; } }

function sugRender(html, cls){
  const b = $("smartBar");
  b.hidden = false;
  b.className = "smartbar" + (cls ? " " + cls : "");
  b.innerHTML = html;
  syncDockNow();
}

/* 隐藏提示条并停止轮询 */
function hideSmartTip(){
  sugStop();
  $("smartBar").hidden = true;
  syncDockNow();
}

/* dock 提示浮层（叠加在主图上、不占文档流）是否显示：只看此刻 smartBar / thinkBar 是否真有内容。
   dockX「收起」只在智能分析建议条可见时出现（汇总分析的处理状态条不提供收起） */
function syncDockNow(){
  const smartOn = appMode === "mark" && !$("smartBar").hidden;
  const thinkOn = appMode === "mark" && !$("thinkBar").hidden;
  $("dockX").hidden = !smartOn || thinkOn;
  document.body.classList.toggle("sugdock-on", smartOn || thinkOn);
}
/* 兼容旧调用点语义：现在 dock 是否展开只取决于是否有提示内容，不再影响图片布局 */
function syncSugDock(){ syncDockNow(); }

/* 仅「未标注」视图展示智能分析建议条：
   标注编辑只在未标注 / 已标注可用，「全部」视图仅浏览过滤、不做标记，故也不做建议与填入 */
function smartTipVisible(){
  return FILTER === "unmarked" && !!cur() && !cur().marked;
}

/* 触发当前未标注图的智能分析（切图 / 清除标记 / 退出汇总分析等时机调用） */
function refreshSmartTip(){
  const it = cur();
  if(!smartTipVisible() || !it){ hideSmartTip(); return; }
  if(sugDismiss.has(it.name)){ hideSmartTip(); return; }   // 本图曾被手动收起 → 不再自动弹出
  const file = it.name;
  const seq = ++sugSeq;
  sugStop();
  sugRender('<span class="spin"></span><span>智能分析中：正在按执行模式同一口径，把该截图与各分类的 14 张对照图（7 张基础图 + 7 张独有区图）做逐像素差异比对…</span>');
  (async () => {
    let taskId = null;
    try{
      const r = await fetch("/api/annotate/think/suggest", {
        method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ file })
      });
      if(!r.ok){ let m = "HTTP " + r.status; try{ m = (await r.text()) || m; }catch(_){} throw new Error(m); }
      taskId = (await r.json()).taskId;
    }catch(e){
      if(seq === sugSeq && smartTipVisible() && cur().name === file){
        sugRender('智能分析失败：' + escHtml(e.message), "bad");
      }
      return;
    }
    if(seq === sugSeq && smartTipVisible() && cur().name === file){
      pollSuggest(seq, file, taskId);
    }
  })();
}

/* 轮询建议任务；期间目标切换 / 已标注 / 进入汇总分析则自动停止 */
function pollSuggest(seq, file, taskId){
  const tick = async () => {
    if(seq !== sugSeq){ return; }
    if(!smartTipVisible() || !cur() || cur().name !== file){ hideSmartTip(); return; }
    let t = null;
    try{
      const r = await fetch("/api/annotate/think/suggest/task/" + encodeURIComponent(taskId), { cache:"no-store" });
      if(r.ok) t = await r.json();
    }catch(_){ /* 服务短暂抖动：下一轮再试 */ }
    if(!t || t.status === "running"){
      sugTimer = setTimeout(tick, 700);
      return;
    }
    if(seq !== sugSeq){ return; }
    if(t.status === "error"){
      sugRender('智能分析失败：' + escHtml(t.message || "未知错误"), "bad");
      return;
    }
    renderSuggest(t.candidates || []);
  };
  tick();
}

/* 渲染智能建议：与执行模式同一口径——差异度 diffPercent = 该分类 14 图不匹配占比的加权平均（独有交集×50 + 交集×30 + 其余 12 张平均×20，再 /100，越小越像） */
function renderSuggest(list){
  if(!list.length){
    sugRender('<span class="sb-title">智能分析</span>' +
      '<span>还没有可参考的对照图：请先在标注模式把同一画面的截图标成同一分类标注（每类 ≥1 张即可，越多越稳），并到「汇总分析」栏生成对照图（生成完整的 14 张图（7 张基础图 + 7 张独有区图）即可参与比对）。</span>');
    return;
  }
  const top = list[0];
  const pct = (typeof top.diffPercent === "number") ? top.diffPercent.toFixed(2) + "%" : "—";
  // 识别已不设阈值门槛（与执行模式一致）：差异度仅作相近程度参考，不再按阈值区分「已识别 / 未识别」
  const actTxt = (top.action && top.action !== "none") ? "（" + escHtml(actLabel(top.action)) + "）" : "";
  const cands = list.slice(0, 3)
    .map(g => '<b>' + escHtml(g.state) + '</b> ' +
        ((typeof g.diffPercent === "number") ? g.diffPercent.toFixed(2) + "%" : "—"))
    .join('　·　');
  const lowNote =
    '<div style="margin-top:6px">差异度越低表示该画面与该分类的样本越接近；若差异度明显偏高，多半是还没有对照样本的新画面——直接人工标注即可把它归入对应分类的样本池。</div>';
  sugRender(
    '<span class="sb-title">智能分析</span>' +
    '<span class="sug">建议分类标注：<b>「' + escHtml(top.state) + '」</b>' + actTxt +
      ' <span style="color:var(--green)">差异度 ' + pct + '（越低越接近样本）</span></span>' +
    '<span class="cand">候选（差异度由小到大）：' + cands + '</span>' +
    '<button class="sb-btn" id="sugAdopt" type="button">填入此分类标注</button>' +
    '<span class="expl">与执行模式完全同一套匹配：把该截图与每个分类的 14 张对照图（7 张基础图：交集/多数/均值/8·32 块图及各自的独有区图）分别同尺度逐点比对。逐点判据按维度类别分两套：交集/多数类（交集/多数/多数块图及各自 -unique）颜色来自样本真实像素，要求逐像素完全一致（R/G/B 三通道差都为 0）；均值类（均值/均值块图及各自 -unique）颜色是样本平均色，走逐通道容差（三通道差都不超过 execute.rgb-dist-threshold 才匹配，默认 255/3=85，任一通道 > 它判「不匹配」）。分类差异度 = 14 张图不匹配点占比的加权平均（独有交集图×50 + 交集图×30 + 其余 12 张图平均×20，再 ÷100，越小越像；交集/独有交集锁定样本核心区，权重最高）；不按识别阈值区分「已识别 / 未识别」，差异度仅供人工标注参考；独有区图只在“该分类独有的画面区域”上计分，专门拉开相近分类的差距，独有像素为空时该维按 0 计；不再使用像素一致率 / 平均色差口径。' + lowNote + '</span>');
  const btn = $("sugAdopt");
  if(btn){
    btn.addEventListener("click", ()=>{
      adoptCategory(top.state);
      $("stateInput").focus();
      toast('已填入分类标注「' + top.state + '」，并自动带入该分类统一的动作与点击坐标（可在图上点一下微调）', "ok");
    });
  }
}

/* ---------------- 事件绑定 ---------------- */
/* dockX：收起当前未标注图的智能分析提示（该图之后不再自动弹出，方便完整查看 / 点选整张图取坐标） */
$("dockX").addEventListener("click", ()=>{
  const it = cur();
  if(it) sugDismiss.add(it.name);
  sugSeq++;                 // 作废可能仍在途的智能分析轮询
  hideSmartTip();
  toast("已收起本图的智能分析提示。", "");
});
document.querySelectorAll("#filterSeg button").forEach(b=>{
  b.addEventListener("click", ()=> applyFilter(b.dataset.f));   // 逻辑见 applyFilter（标签“数字”过滤共用）
});

/* 「全部」视图右栏底部「修改这张图」：一键跳转到当前图所属的未标注/已标注视图并进入标注编辑 */
$("btnJumpMark").addEventListener("click", jumpToEdit);

/* 汇总分析七图：load 后按“原图一半”排版；error 时若仍为当前分组目标则自动重试；单击卡片打开 80% 视口弹窗 */
for(const id of IMG_IDS){
  const el = $(id);
  el.addEventListener("load", ()=>{
    if(thinkImgState(id).seq !== thinkSeq) return;   // 已切到其它分组：过期图的 load 不参与排版
    applyCardLayout(el);
  });
  el.addEventListener("error", ()=> onThinkImgError(el));
  el.addEventListener("click", ()=>{
    if(!el.getAttribute("src")) return;
    const card = el.closest(".tcard");
    const tn = card && card.querySelector(".tch .tn");
    const cap = (tn && tn.textContent) || id;
    openLightbox(el.src, cap);
  });
}
const lb = $("imgLightbox");
$("lbImg").addEventListener("load", ()=>{
  lbNatural = { w: $("lbImg").naturalWidth || 0, h: $("lbImg").naturalHeight || 0 };
  applyLbZoom();
});
$("lbClose").addEventListener("click", (e)=>{
  e.stopPropagation();
  closeLightbox();
});
lb.addEventListener("click", (e)=>{
  if(e.target.closest(".lbBox")){ toggleLbMode(); return; }   // 弹窗内单击图片：自适应缩放 ↔ 原始分辨率
  closeLightbox();                                             // 点空白处关闭
});
document.addEventListener("keydown", (e)=>{
  if(e.key === "Escape") closeLightbox();
});

$("btnCap").addEventListener("click", toggleCap);
$("btnLog").addEventListener("click", openLogPanel);
$("btnExit").addEventListener("click", requestExit);
$("btnRebuild").addEventListener("click", rebuildThink);
$("btnSaveNext").addEventListener("click", ()=> saveCurrent(true));
$("btnLast").addEventListener("click", useLastMark);
$("btnClear").addEventListener("click", clearCurrent);
$("btnDelete").addEventListener("click", deleteCurrent);
$("zmOrig").addEventListener("click", ()=> setMainMode("orig"));
$("zmFit").addEventListener("click", ()=> setMainMode("fit"));
$("stateInput").addEventListener("input", ()=>{
  const el = $("stateInput");
  if(BAD_LABEL.test(el.value)) el.value = cleanLabel(el.value);   // 无法作为文件名的符号直接剔除
  setDirty(); updateTagActive();
});
document.querySelectorAll('input[name=action]').forEach(r=>{
  r.addEventListener("change", ()=>{ if(r.checked) setAction(r.value, true); });
});

document.addEventListener("keydown", (e)=>{
  if(appMode !== "mark") return;                // 执行模式下不响应标注类快捷键
  const tag = (document.activeElement && document.activeElement.tagName || "").toLowerCase();
  const typing = tag==="input" || tag==="textarea" || tag==="select";
  if(e.key==="ArrowUp" && !typing){ e.preventDefault(); navStep(-1); }
  else if(e.key==="ArrowDown" && !typing){ e.preventDefault(); navStep(1); }
  else if(e.key==="Enter" && !typing){
    if(tag==="button" || tag==="a") return;        // 让按钮/链接自己响应 Enter
    e.preventDefault();
    // 仅未标注 / 已标注视图可编辑保存；全部（只浏览过滤）、汇总分析模式 Enter 不保存
    if(FILTER === "unmarked" || FILTER === "marked") saveCurrent(true);
  }
});

window.addEventListener("resize", ()=>{
  if($("imgwrap").style.display !== "none" && naturalW) applyMainZoom();
  renderDot();
});

/* ---------------- 自动刷新 ---------------- */
const POLL_MS = 10000;   // 后台每 10 秒悄悄同步一次列表
function listSig(arr){ return arr.map(i => [i.name,i.marked,i.state,i.action,i.left,i.top].join("|")).join("\n"); }
async function refreshSilent(){
  let arr;
  try{ arr = await fetchAllSafe(); }catch(e){ return false; }
  if(listSig(ALL) === listSig(arr)) return true;   // 无实质变化则不重绘，避免打扰
  ALL = arr;
  try{ DEF = await fetchDefs(); }catch(_){}
  rebuildStates();
  renderList();
  updateNavButtons();
  return true;
}

/* 汇总分析视图下的静默同步：只刷新顶部「全部 / 未标注 / 已标注 / 汇总分析」计数，不重建任何列表。
   分组列表由 refreshThink 统一重绘，新截图落盘只影响计数、不应整列重建打扰查看对照图 */
async function updateCountsOnly(){
  let arr;
  try{ arr = await fetchAllSafe(); }catch(e){ return; }
  if(listSig(ALL) === listSig(arr)) return;
  ALL = arr;
  setSegState();
}
async function pollTick(){
  if(appMode !== "mark") return;                // 执行模式：暂停后台列表静默同步
  if(FILTER === "think"){                       // 汇总分析模式：先同步计数，再静默刷新组合状态
    if(!thinkBusy && !dirty){
      await updateCountsOnly();                 // 新截图 / 新标注 → 「全部 / 未标注 / 已标注」计数自动更新
      await refreshThink(false, true);
    }
    return;
  }
  if(dirty) return;                             // 编辑中不发请求，保存时统一同步
  await refreshSilent();
}

/* 页面隐藏期间浏览器会收紧后台定时器：从后台回到前台 / 窗口重新可见时立即同步一次，
   让「全部 / 未标注 / 汇总分析」的计数与产物尽快追上最新状态，不用等下一次轮询 */
document.addEventListener("visibilitychange", ()=>{
  if(document.hidden) return;
  if(appMode !== "mark" || dirty) return;
  if(FILTER === "think"){
    if(!thinkBusy){ updateCountsOnly(); refreshThink(false, true); }
  }else{
    refreshSilent();
  }
});

/* ---------------- 服务端版本检测：后端重新打包/重启后自动刷新页面 ---------------- */
const META_MS = 2000;      // 每 2 秒探一次：既做版本/存活探测，也借 savedSeq 感知“新截图已保存”以即时刷新列表
const META_DOWN_LIMIT = 8; // 连续这么多次探不到后端即判定“程序已退出”（容忍快速重启的间隙）
let baseCodeTs = null;     // 页面打开时记录的后端代码构建时间（基线）
let lastSavedSeqShown = null;  // 已同步过的截图保存计数（基线；变化 = 有新截图落盘，需刷新列表）
let pendingReload = false; // 编辑未保存时挂起的刷新（保存后再执行）
let metaDown = 0;          // 连续探测失败计数
let metaGone = false;      // 是否已判定程序退出（只触发一次）
let needExitUI = false;    // 判定发生在后台时，等页面回到前台再弹“已退出”提示并自关

async function checkAppVersion(){
  if(pendingReload) return;   // 页面在后台也继续探活：后端退出后仍能按时间判定，避免计数被“切走”冻结
  try{
    // shotAfter = 已并入历史日志的最后一条截图结果 seq：后端把其后新增的截图结果（shotLog）一并返回；
    // 首次为 -1 → 全量回填启动以来全部截图结果（补齐轮询间隙被节流的中间条，供历史日志回溯）
    const r = await fetch("/api/app/meta?shotAfter=" + lastShotLogSeq, { cache:"no-store" });
    if(!r.ok) return;
    const j = await r.json();
    metaDown = 0;                        // 探测成功：清零连续失败计数
    // 截图 resize 持续不达标 → 后端已自动暂停：弹窗提示并同步按钮态（仅提示一次，用户 resume 后可再次提示）
    const stopReason = j && j.captureStopReason;
    if(stopReason && stopReason !== lastStopReasonShown){
      lastStopReasonShown = stopReason;
      if(!capPaused){ capPaused = true; renderCapBtn(); }   // 后端已暂停：按钮恢复为「开启截图」
      showCapStopModal(stopReason);
    }
    // 截图结果历史（shotLog）：后端按发生顺序保留每一轮「保存 / 差异过小丢弃」结果，本请求按 seq 增量拉取。
    // 每条都写入历史日志（showShotTip / pushLog）；只把最新一条作右下角单条替换式轻提示，
    // 首次全量回填积压时不逐条闪屏（旧页面无 shotLog 字段时静默跳过，兼容旧后端）
    const shotLog = Array.isArray(j && j.shotLog) ? j.shotLog : [];
    if(shotLog.length){
      const pctTxt = v => {
        let p = (v != null ? v : (capDiffThreshold || 0));
        if(Number.isInteger(p)) return String(p);           // 整数直显（阈值常为 1/3，如 3 → "3"）
        return String(Math.round(p * 100) / 100);           // 非整数保留两位去尾零：0.96 → "0.96"（一位会把 0.96~1% 舍成 1%，与阈值并排观感矛盾）
      };
      for(let i = 0; i < shotLog.length; i++){
        const s = shotLog[i];
        const saved = s.kind === "saved";
        // dup：参考图是 classify/ 已标注样本时带出分类，capture/ 未标注图则只报文件名
        const fname = s.name || "参考图";
        const refWho = s.refState ? "「" + s.refState + "」分类的截图「" + fname + "」" : "截图「" + fname + "」";
        const txt = saved
          ? "已保存截图 " + fname
          : "当前画面与" + refWho + "差异为 " + pctTxt(s.pct) + "%，小于阈值 " + pctTxt(s.threshold) + "%，不保存";
        if(i === shotLog.length - 1) showShotTip(txt, saved ? "ok" : "skip");   // 最新一条：右下角轻提示（内部已入日志）
        else pushLog(txt, saved ? "ok" : "skip", Number(s.at) || undefined);    // 轮询间隙的中间条：仅入历史日志
      }
      lastShotLogSeq = Number(shotLog[shotLog.length - 1].seq) || lastShotLogSeq;
    }
    // 后端重启后 seq 会从 1 重新计数（历史已清空）：本页基线若已越过它则说明计数跳变，下一轮改为全量回填，避免提示静默中断
    const maxSeq = Number(j && j.shotMaxSeq) || 0;
    if(maxSeq > 0 && lastShotLogSeq > maxSeq) lastShotLogSeq = -1;
    // 启动历史重复清理结果：后端每次启动按差异阈值（默认 3%）重扫 capture/ + classify/ 全部截图、
    // 删除差异低于阈值的重复图（只保留最早一张）。不论是否删除了图片都右下角提示一次清理完成
    const dedup = j && j.startupDedupNotice;
    if(dedup && dedup.at && Number(dedup.at) !== lastDedupNoticeAt){
      lastDedupNoticeAt = Number(dedup.at);
      let pct = (dedup.threshold != null ? dedup.threshold : (capDiffThreshold || 0));
      if(Number.isInteger(pct)){ pct = String(pct); }
      else{ pct = String(Math.round(pct * 10) / 10); }   // 阈值如 3.0% → 显示“3%”
      // 耗时（后端实际重扫毫秒数）：≥1s 显示 “Xs”，不足 1s 显示 “Xms”；旧后端无该字段时静默不加
      const costMs = Number(dedup.costMs) || 0;
      const costTxt = costMs > 0
        ? "，耗时 " + (costMs >= 1000 ? (costMs / 1000).toFixed(1).replace(/\.0$/, "") + "s" : costMs + "ms")
        : "";
      const msg = (dedup.removed > 0)
        ? "启动重复清理：按差异 < " + pct + "% 阈值重扫 " + dedup.scanned + " 张截图，删除重复 " + dedup.removed + " 张" + costTxt
        : "启动重复清理：按差异 < " + pct + "% 阈值重扫 " + dedup.scanned + " 张截图，检查完成，未发现重复图片" + costTxt;
      showShotTip(msg, "ok");
    }
    const ts = Number(j && j.codeTs) || 0;
    if(!ts) return;
    if(baseCodeTs === null){ baseCodeTs = ts; lastSavedSeqShown = Number(j && j.savedSeq) || 0; return; }   // 首次：只记基线（含截图计数），不做比对
    // 后端保存计数递增 = 刚有一张新截图落盘：立即静默刷新顶部计数，无需等 10s 后台轮询；
    // 编辑中 / 执行模式下不抢，避免打扰当前操作（保存成功那一步本来就会统一同步列表）。
    // 汇总分析视图只刷计数（新截图不改动分组列表，分组在 10s 轮询里自行刷新）；
    // 普通截图视图才整列重建，让挂机期间落盘的新截图与数量即时可见。
    const saved = Number(j && j.savedSeq) || 0;
    if(saved !== lastSavedSeqShown){
      lastSavedSeqShown = saved;
      // 保存采用「.tmp 写入 → 原子改名 .png」：.png 一旦出现即完整，列表按后缀即可即时列出，无需延迟补刷
      if(!dirty && appMode === "mark"){
        if(FILTER === "think") updateCountsOnly();
        else refreshSilent();
      }
    }
    if(ts === baseCodeTs) return;
    baseCodeTs = ts;                                       // 已变化：去重，避免反复提示
    if(dirty){
      pendingReload = true;
      toast("检测到服务端代码已更新。当前标注尚未保存：保存并下一张、清除标记或删除后，会自动刷新加载新版页面。", "ok");
    } else {
      toast("检测到服务端代码已更新，正在刷新页面…", "");
      allowReloadClose = true;               // 放行 beforeunload，避免代码刷新被关闭确认拦截
      setTimeout(()=> location.reload(), 500);
    }
  }catch(e){
    /* 探测失败：短时间失败可能是服务重启间隙；连续多次失败 = 程序已退出 → 提示并尝试自关 */
    if(exiting || metaGone) return;
    if(++metaDown >= META_DOWN_LIMIT){
      metaGone = true;
      if(document.hidden){ needExitUI = true; return; }  // 后台先不打扰/不误关，回到前台立即提示
      showExitScreen();
      armPageClose(1200);   // 先让“已退出”提示可见，再尝试关页
    }
  }
}

/* dirty 被清除（保存/清除/删除成功）后调用：若之前挂起了刷新则现在执行 */
function maybeAutoReload(){
  if(pendingReload){
    pendingReload = false;
    toast("已保存，正在刷新加载新版页面…", "ok");
    allowReloadClose = true;               // 放行 beforeunload，避免代码刷新被关闭确认拦截
    setTimeout(()=> location.reload(), 400);
  }
}

document.addEventListener("visibilitychange", ()=>{
  if(document.hidden) return;
  if(needExitUI){                          // 后台已判定程序退出：回到页面立即展示提示并尝试自关
    needExitUI = false;
    showExitScreen();
    armPageClose(1200);
    return;
  }
  if(baseCodeTs !== null) checkAppVersion();   // 切回页面立刻复核一次
});

/* ---------------- 标注 / 执行 模式切换 ---------------- */
let appMode = "mark";                       // 当前工作模式：mark=默认标注模式 / exec=执行模式（实时画面识别 + 动作执行）

function setAppMode(m){
  appMode = m;
  document.body.setAttribute("data-appmode", m);
  syncSugDock();
  if(m === "exec"){
    toast("已切换到「执行模式」：立即识别当前画面，命中后一键执行点击。", "ok");
    syncCapStatus();                                  // 右上角截图开关沿用标注模式同款状态（文案/高亮与后端一致）
    if(typeof execOnModeChange === "function") execOnModeChange();
  } else {
    if(typeof execAutoStop === "function") execAutoStop();   // 离开执行模式：结束自动识别循环
    toast("已回到「标注模式」。", "ok");
    refreshSilent();     // 隐藏期间可能新增了截图：切回时同步一次列表
    syncRightPanel();    // 右栏按当前视图重置（可能停留在“全部”→ 显示分类过滤）
    syncCapStatus();     // 同步截图运行状态（开启/暂停按钮的文案与高亮）
    if(FILTER === "think"){ refreshThink(true, true); }   // 切回仍停在「汇总分析」：同手动点 tab，自动补分析待生成的组合
  }
}

// 下拉框选中即切换（合拢时下拉本身显示当前模式）
$("modeSel").addEventListener("change", ()=>{
  const sel = $("modeSel");
  if(!sel) return;
  const want = sel.value === "exec" ? "exec" : "mark";
  if(want === appMode) return;
  if(want === "exec" && dirty && curName){   // 有未保存标注：提示先处理，避免编辑内容丢失
    toast("当前标注尚未保存：请先「保存并下一张」或「清除标记」，再切换到执行模式。", "err");
    sel.value = appMode;                     // 回滚下拉选择
    return;
  }
  setAppMode(want);
});

/* ---------------- 执行模式：实时画面识别 + 动作执行（驱动 /api/execute/*；单次识别，无后台循环） ---------------- */
let execClickMode = "post";     // 后端配置的点击方式（/api/execute/status.clickMode：post=后台消息 / screen=前台点击）
let execLatest = null;          // 最近一次 /api/execute/latest 的返回
let execShownAt = 0;            // 当前画面对应快照的 at（与 /api/execute/frame 配对）
let execShownW = 0, execShownH = 0;   // 已展示画面的自然尺寸
let execImgReady = false;       // 当前是否已有可展示的画面
let execActBusy = false;        // “执行动作”进行中（防连点）
let execShownMarked = false;    // 当前画面是否已「存入分类」过（防同一帧重复标记）
let execPending = false;        // 是否有「立即识别 / 进入即识别」一轮在途：在途时中央保持转圈，不让占位文案覆盖
let execScanningOn = false;     // 中央是否正处于「正在截图识别…」转圈态（轮询期间避免反复重建动画）
let execFrameRetries = 0;       // 画面帧瞬时加载失败的重试计数（快照刚被替换时短暂出现，最多重试 3 次）
const execPollMs = 1500;
let execAutoOn = false;     // 自动识别循环运行中？（红色按钮开关：运行时会自动 截图→确认→动作→响应等待 循环）
let execAutoSeq = 0;        // 自动识别「代」序号：开/关时自增，用于让停止前仍在途的旧轮自动退出

const execEsc = s => String(s == null ? "" : s).replace(/[&<>"']/g,
  c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const execClock = t => { const d = new Date(t); const p = n => String(n).padStart(2, "0");
  return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds()); };

async function execGet(url, opt){
  try{
    const r = await fetch(url, Object.assign({ cache:"no-store" }, opt || {}));
    if(!r.ok) return null;
    return await r.json();
  }catch(_){ return null; }
}

/* ---- 参数同步（点击模式等） ---- */
async function execSyncStatus(){
  const j = await execGet("/api/execute/status");
  if(j && (j.clickMode === "screen" || j.clickMode === "post")) execApplyMode(j.clickMode);
}
function syncExecModeUi(){
  const v = (execClickMode === "post") ? "post" : "screen";
  document.querySelectorAll('input[name="execModeOpt"]').forEach(r => { r.checked = (r.value === v); });
}
function execApplyMode(mode){
  if(mode !== "screen" && mode !== "post") return;
  execClickMode = mode;
  syncExecModeUi();
  renderExecActBtn(execLatest || null, !!(execLatest && execLatest.recognized && execLatest.action === "click"
      && Number.isInteger(execLatest.left) && Number.isInteger(execLatest.top)));
}
async function execRefreshOnce(){
  const b = $("execRefresh");
  if(!b || b.disabled) return;
  execPending = true;                                 // 本轮识别在途：期间中央保持「正在截图识别…」
  b.disabled = true;
  if($("execAct")) $("execAct").disabled = true;      // 识别进行中暂时不可执行，避免对旧画面误点
  if(!execFrameShown()) execShowScanning();           // 尚无画面：把中间提示换成「正在截图识别…」
  let j = null;
  try{
    j = await execGet("/api/execute/refresh", { method:"POST" });
  } finally {
    b.disabled = false;
    execPending = false;
  }
  if(!j){
    toast("识别失败：接口不可用", "err");
    if(!execFrameShown()) execShowScanning();         // 识别失败且从未出图：保持转圈，不显示“请先点立即识别”类误导文案
    return;
  }
  execLatest = j;
  renderExecAll();
  toast("已按当前画面完成一次识别", "ok");
}

/* ---- 拉取并渲染最新快照 ---- */
async function execLoadLatest(){
  const j = await execGet("/api/execute/latest");
  if(!j) return false;
  execLatest = j;
  renderExecAll();
  return execFrameShown();     // 是否已展示出真实画面（无画面时中间只显示占位提示）
}
/* 画面区当前是否已展示出图片 */
function execFrameShown(){
  const w = $("execWrap");
  return !!(w && w.style.display !== "none");
}
/* 中间占位提示：正在截图识别（首次进入 / 手动刷新但尚无画面时显示，避免出现「尚未识别，请手动点击」的误导文案） */
function execShowScanning(){
  const ph = $("execPlaceholder"); if(!ph || execScanningOn) return;
  execScanningOn = true;
  ph.style.display = "";
  ph.innerHTML =
    '<div style="text-align:center;padding:24px">' +
      '<div class="execspin" style="width:26px;height:26px;margin:0 auto 12px;border:3px solid rgba(255,255,255,.15);border-top-color:var(--green);border-radius:50%;animation:execspin 1s linear infinite"></div>' +
      '<div style="font-size:13.5px">正在截图识别…</div>' +
      '<div style="margin-top:8px;font-size:12px;color:#5b6577;line-height:1.7">正在抓取目标窗口最新画面并与已标注分类比对，请稍候。</div>' +
    '</div>';
}
function execSetVal(id, txt, cls){
  const e = $(id); if(!e) return;
  e.textContent = txt;
  e.className = "v" + (cls ? " " + cls : "");
}

function renderExecAll(){
  const j = execLatest;
  if(!j) return;
  const meta = $("execMetaChip");
  if(meta) meta.textContent = (j.imageWidth > 0 && j.imageHeight > 0)
      ? (j.imageWidth + "×" + j.imageHeight) : "—";

  // 尚无可展示画面且不是已确认的失败：识别一轮在途，或后端只是“还没产生过结果”（占位快照）→ 属等待态
  const idleWait = execPending || String(j.error || "").indexOf("尚未产生识别结果") === 0;

  // 「匹配分类」= 识别命中的分类标注（state）：动作 / 点击坐标都取自该分类（summary/<分类>/info.json）。
  // 产物目录常规与分类标注同名（一个分类 = 一个比对分组），为避免同一名字在面板上出现两次，
  // 只在二者确不相同（历史数据同分类多动作遗留的“<分类>_<action>”目录）时才追加目录名说明。
  // 匹配分类 = 差异分值最低的最近似分类；分值仅作参考、不设识别阈值，不再区分“已识别/未识别”
  let matchedTxt = "—", matchedCls = "";
  if(!idleWait && !(j.error && j.imageWidth <= 0 && !j.windowFound)){
    if(j.state){
      matchedTxt = j.state; matchedCls = "";
    }
  }
  if(matchedTxt !== "—" && j.state && j.matchedSample && j.matchedSample !== j.state){
    matchedTxt += "（产物目录 " + j.matchedSample + "）";
  }
  execSetVal("execMatched", matchedTxt, matchedCls);
  // 耗时只在已产生可展示识别结果（命中 / 最近似某分类）时显示；
  // 首进尚无结果（或本次没比到任何分类）时不亮，保持 —，截图识别完成后随整面板一起刷新。
  // captureMs = 整轮（截图+识别+组装）总耗时，classifyMs = 其中纯识别比对耗时，主值括号标注识别子项更科学
  execSetVal("execCost", (matchedTxt !== "—" && j.captureMs >= 0 && j.classifyMs >= 0)
      ? (j.captureMs + " ms（识别 " + j.classifyMs + " ms）") : "—");

  // 动作/坐标取自最近似分类定义，可点与否不再受“识别阈值”门禁（差异分值仅作参考）；
  // 分类定义了鼠标点击且有坐标即可展示执行，仅产物确无坐标时才提示回标注模式补齐
  const clickable = !!(j.action === "click" && Number.isInteger(j.left) && Number.isInteger(j.top));
  if(j.action === "click"){
    execSetVal("execAction", clickable ? "鼠标点击" : "鼠标点击（该分类尚无点击坐标，请回标注模式点选）", clickable ? "ok" : "err");
  } else if(matchedTxt !== "—"){
    // 「无动作」来自该分类的定义：只有确实命中 / 最近似某分类时才展示；
    // 尚未识别出分类（占位快照 / 无可比分类 / 识别失败）时保持 —，避免无依据的“无动作”
    execSetVal("execAction", "无动作", "");
  } else {
    execSetVal("execAction", "—", "");
  }
  execSetVal("execPos", clickable ? "(" + j.left + ", " + j.top + ")" : "—");
  execSetVal("execDiff", (typeof j.bestDiffPercent === "number" && j.bestDiffPercent >= 0)
      ? j.bestDiffPercent.toFixed(2) + "%" : "—", "ok");
  if((j.totalSamples || 0) > 0){
    // 存在可比的分类总数时才显示分组进度；一个分类都没有（首次进入尚无产物）时保持 —，
    // 否则“已比对 0 / 全部 0 个分类”是无意义的无效信息
    execSetVal("execSamples", "已比对 " + (j.scannedSamples || 0) + " / 全部 " + j.totalSamples + " 个分类",
        j.scannedSamples > 0 && j.totalSamples > j.scannedSamples ? "err" : "");
  } else {
    execSetVal("execSamples", "—", "");
  }
  execSetVal("execWin", j.windowFound
      ? (j.windowTitle || "已找到窗口")
      : (idleWait ? "正在识别（尚未产生结果）…" : (j.error || "未找到目标窗口")),
      (j.windowFound || idleWait) ? "" : "err");

  renderExecCandidates(j.candidates || []);
  renderExecActBtn(j, clickable);
  renderExecSaveCap(j);

  if(j.imageWidth > 0 && j.at !== execShownAt){
    execShownAt = j.at;
    execShownMarked = false;      // 新画面：允许再次「存入分类」
    execLoadFrame();
  } else if(j.imageWidth > 0){
    renderExecMarkers();      // 同一帧：只刷新标记/缩放
  } else if(idleWait){
    // 识别在途 / 尚未产生过结果（占位快照）：画面区保持「正在截图识别…」转圈，
    // 不要用“请先点立即识别”这类文案覆盖——进入执行模式 / 自动识别本身无需手动操作，误导且打断等待。
    execShowScanning();
  } else {
    // 已确认的失败原因（未找到窗口 / 已最小化 / 截图失败等）→ 展示具体原因文案。
    execSetPlaceholder(j.error || "当前没有可显示的画面");
  }
}

function renderExecActBtn(j, clickable){
  const b = $("execAct");
  if(!b) return;
  if(execAutoOn){ b.disabled = true; return; }   // 自动识别运行中：禁用手动「执行动作」，避免双触发
  b.disabled = !clickable || execActBusy;
  b.textContent = "执行动作";
  b.title = clickable
      ? (execClickMode === "screen"
          ? "直接按右侧识别结果做一次真实鼠标点击，不再重新截图识别（画面已变化请先点「立即识别」；前台点击要求窗口可见、不被遮挡）"
          : "直接按右侧识别结果向目标窗口后台投递完整点击消息序列：滑入移动→按下→抬起，不再重新截图识别（画面已变化请先点「立即识别」；后台消息模式不抢前台）")
      : "识别到「鼠标点击」动作后按钮可用，点击坐标会标在画面上";
}

/* 「把当前画面存入待标注」可用态：有画面且不在自动识别循环中
   （自动循环每秒都在换帧，此时保存可能存的不是当前看到的那张） */
function renderExecSaveCap(j){
  const b = $("execSaveCap"); if(!b) return;
  const can = !execAutoOn && !!(j && j.imageWidth > 0 && j.imageHeight > 0);
  b.disabled = !can;
}

/* 把当前画面另存为 capture/ 原始截图（未标注）：切到「标注模式 → 未标注」即可定位并按正常流程精确标注 / 修正坐标 */
async function execSaveToCapture(){
  const b = $("execSaveCap");
  if(!b || b.disabled || execAutoOn) return;
  b.disabled = true;
  const old = b.textContent;
  b.textContent = "保存中…";
  const j = await execGet("/api/execute/save-to-capture", { method:"POST" });
  b.textContent = old;
  renderExecSaveCap(execLatest);
  if(j && j.ok){
    toast("已把当前画面存入 capture/（" + j.name + "）。切到「标注模式 → 未标注」即可定位并精确标注（含鼠标点击坐标）。", "ok");
  } else if(j && j.kind === "dup"){
    // 与已保存截图差异不达标被拦截（非系统错误）：用琥珀「跳过」样式提示，文案与差异比例由后端给出
    toast(j.message || "当前画面与某张已保存截图几乎相同，本次未保存", "skip");
  } else {
    toast("保存失败：" + ((j && j.message) || "接口不可用"), "err");
  }
}

function renderExecCandidates(list){
  const box = $("execCandidates"); if(!box) return;
  box.innerHTML = "";
  if(!list || !list.length){
    box.innerHTML = '<div class="hint">暂无可用比对分组：请在标注模式为每个分类保存 ≥1 张同尺寸样本，并到「汇总分析」生成对照图（汇总分析完成即可参与识别）。</div>';
    return;
  }
  const done = execShownMarked;
  list.forEach((it, i) => {
    const row = document.createElement("div");
    row.className = "cand" + (i === 0 ? " top" : "");
    const stateTxt = execEsc(it.state || "—");
    const diffTxt = (typeof it.diffPercent === "number" && it.diffPercent >= 0) ? it.diffPercent.toFixed(2) + "%" : "—";
    const fileTxt = (it.matchedFile && it.matchedFile !== it.state)
        ? ' <span style="color:#556;font-size:11px">' + execEsc(it.matchedFile) + "</span>" : "";
    row.innerHTML = '<span class="cst">' + stateTxt + fileTxt + "</span>" +
                    '<span class="cd">' + diffTxt + "</span>";
    if(Array.isArray(it.kinds) && it.kinds.length){
      const vbtn = document.createElement("button");
      vbtn.type = "button";
      vbtn.className = "mbtn";
      vbtn.textContent = "详细分值";
      vbtn.title = "查看该分类 14 张对照图（基础图及其独有区图）各自的不匹配点占比分值";
      vbtn.addEventListener("click", () => openKindScores(it));
      row.appendChild(vbtn);
    }
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "mbtn";
    btn.disabled = done;
    btn.textContent = done ? "已存入" : "存入分类";
    btn.title = done
      ? "当前画面已作为样本存入分类；重新「立即识别」出新画面后可再次标记"
      : "把当前画面登记为「" + stateTxt + "」的样本，下次识别会优先参考它";
    btn.addEventListener("click", () => execQuickMark(it.state, btn));
    row.appendChild(btn);
    box.appendChild(row);
  });
}

/* ---- 各对照图分值明细弹层：某候选分类 summary 产物目录里各张对照图（7 基础图 + 7 -unique 独有区图）的分值 ---- */
function openKindScores(it){
  if(!it || !Array.isArray(it.kinds) || !it.kinds.length){
    toast("该候选缺少对照图分值明细（未参与比对）", "err");
    return;
  }
  if($("kindScoreModal")) $("kindScoreModal").remove();
  const zh = { same:"交集图", "same-unique":"独有交集图",
               max:"多数图", "max-unique":"独有多数图",
               avg:"均值图", "avg-unique":"独有均值图",
               m8:"多数块 8×8", "m8-unique":"独有多数块 8×8",
               a8:"均值块 8×8", "a8-unique":"独有均值块 8×8",
               m32:"多数块 32×32", "m32-unique":"独有多数块 32×32",
               a32:"均值块 32×32", "a32-unique":"独有均值块 32×32" };
  // kind → 磁盘文件名（多数系列在磁盘上是 major 名：major / major8 / major32 及各自 -unique）
  const kf = { same:"same.png", "same-unique":"same-unique.png",
               max:"major.png", "max-unique":"major-unique.png",
               avg:"avg.png", "avg-unique":"avg-unique.png",
               m8:"major8.png", "m8-unique":"major8-unique.png",
               a8:"avg8.png", "a8-unique":"avg8-unique.png",
               m32:"major32.png", "m32-unique":"major32-unique.png",
               a32:"avg32.png", "a32-unique":"avg32-unique.png" };
  const rows = it.kinds.map(ks => {
    const nm = zh[ks.kind] || ks.kind;
    const isBlock = ks.kind === "m8" || ks.kind === "m8-unique" || ks.kind === "a8"
      || ks.kind === "a8-unique" || ks.kind === "m32" || ks.kind === "m32-unique"
      || ks.kind === "a32" || ks.kind === "a32-unique";
    const grid = (typeof ks.w === "number" && ks.w > 0 && typeof ks.h === "number" && ks.h > 0)
        ? (isBlock ? "块网格 " : "产物尺寸 ") + ks.w + "×" + ks.h : "—";
    const score = (typeof ks.score === "number" && ks.score >= 0)
        ? '<b style="color:var(--green)">' + ks.score.toFixed(2) + "%</b>"
        : '<span style="color:#778" title="该图不可比：产物缺失/解码失败，或基础图公共区全空（无有效比对像素）。本轮未计入差异分值（按 0 计，不报错）。独有区图不在此列：0 个独有点按 0.00% 计，有 ≥1 个独有点即正常计分">跳过</span>';
    return '<div style="display:flex;justify-content:space-between;align-items:center;gap:14px;padding:7px 2px;border-bottom:1px solid var(--border);font-size:12.5px">' +
      '<span>' + escHtml(nm) +
        '<span style="color:#667;font-size:11px;margin-left:7px">' + escHtml((kf[ks.kind] || ks.kind + ".png") + " · " + grid) + "</span></span>" +
      '<span style="white-space:nowrap">' + score + "</span></div>";
  }).join("");
  const ov = document.createElement("div");
  ov.id = "kindScoreModal";
  ov.className = "modal-ov";
  ov.innerHTML =
    '<div class="xcard" style="max-width:580px;width:100%;text-align:left;position:relative">' +
      '<div class="xt2">对照图分值</div>' +
      '<div style="color:var(--muted);font-size:11.5px;line-height:2;margin:2px 0 10px">' +
        "标注分类：" + escHtml(it.state || "—") + "<br>" +
        "差异分值计算：该图的非透明区域与当前画面逐点比对的不匹配点占比<br>" +
        "色差按维度类别分两套：交集/多数类（交集/多数/多数块图及各自 -unique）逐像素完全一致（三通道差都为 0）；均值类（均值/均值块图及各自 -unique）走逐通道容差（三通道差都不超过 execute.rgb-dist-threshold（默认 255/3=85）才一致）</div>" +
      '<div style="max-height:min(46vh,320px);overflow:auto;padding-right:4px">' + rows + "</div>" +
      '<div style="text-align:center;margin-top:12px"><button type="button" class="btn" id="kindsOk">知道了</button></div>' +
    "</div>";
  document.body.appendChild(ov);
  const close = () => ov.remove();
  ov.addEventListener("click", e => { if(e.target === ov) close(); });   // 点弹层外（旁边）即关闭
  const ok = $("kindsOk"); if(ok) ok.addEventListener("click", close);
}

/* ---- 快速标记：把当前画面另存为所选分类的样本（识别错了 → 立即纠正） ---- */
async function execQuickMark(state, btn){
  if(!state || btn.disabled) return;
  btn.disabled = true;
  btn.textContent = "存入中…";
  const j = await execGet("/api/execute/mark", {
    method:"POST", headers:{ "Content-Type":"application/json" },
    body: JSON.stringify({ state: state })
  });
  if(j && j.ok){
    execShownMarked = true;
    toast("已把当前画面存入分类「" + state + "」的样本。需在标注模式对该分类重新执行「汇总分析」，后续识别才会按新样本匹配。", "ok");
  } else if(j && j.kind === "dup"){
    // 与既有样本差异不达标被拦截（非系统错误）：用琥珀「跳过」样式提示，文案与差异比例由后端给出
    btn.disabled = false;
    btn.textContent = "存入分类";
    toast(j.message || "当前画面与既有样本几乎重复，未存入分类（可在标注模式修改既有样本的分类）", "skip");
  } else {
    btn.disabled = false;
    btn.textContent = "存入分类";
    toast("标记失败：" + ((j && j.message) || "接口不可用"), "err");
  }
}

/* ---- 画面展示 / 点击点标记 ---- */
function execSetPlaceholder(msg){
  execScanningOn = false;
  const ph = $("execPlaceholder"), w = $("execWrap");
  if(ph){
    ph.innerHTML = '<div style="text-align:center;padding:24px"><div class="hint">' + execEsc(msg) + "</div></div>";
    ph.style.display = "";
  }
  if(w) w.style.display = "none";
  execImgReady = false;
}

function execLoadFrame(){
  const img = $("execImg"), ph = $("execPlaceholder"), w = $("execWrap");
  if(!img || !ph || !w) return;
  img.onload = () => {
    execFrameRetries = 0;
    execScanningOn = false;                 // 已有真实画面：退出转圈态
    ph.style.display = "none";
    w.style.display = "inline-block";
    execShownW = img.naturalWidth || 0;
    execShownH = img.naturalHeight || 0;
    execImgReady = execShownW > 0;
    execFitImage();
  };
  img.onerror = () => {
    // 后端一轮截图/识别可能正占用较长：快照刚被替换时 /frame 可能短暂取不到对应 PNG，自动重试几次
    if(execFrameRetries < 3 && execLatest && execLatest.at === execShownAt && execLatest.imageWidth > 0){
      execFrameRetries++;
      setTimeout(execLoadFrame, 800);
    } else {
      execFrameRetries = 0;
      execSetPlaceholder("最新画面加载失败（快照刚被替换时短暂出现，会自动重试）");
    }
  };
  img.src = "/api/execute/frame?at=" + execShownAt + "&t=" + Date.now();
}

function execFitImage(){
  const img = $("execImg"), area = $("execArea");
  if(!img || !area || !execShownW || !execImgReady) return;
  const pad = 24;
  const aw = Math.max(120, area.clientWidth - pad);
  const ah = Math.max(120, area.clientHeight - pad);
  let s = Math.min(aw / execShownW, ah / execShownH);
  if(s > 1) s = 1;                       // 默认不放大，保持原图清晰
  img.style.width  = Math.max(1, Math.round(execShownW * s)) + "px";
  img.style.height = Math.max(1, Math.round(execShownH * s)) + "px";
  renderExecMarkers();
}

function renderExecMarkers(){
  const j = execLatest, img = $("execImg");
  if(!j || !img || !execShownW || !execImgReady){ return; }
  // 与面板口径一致（最近似分类定义了鼠标点击且有坐标即标；CSS 默认 display:none，故显式用 block 才不会被样式表盖回去）
  const clickable = !!(j.action === "click" && Number.isInteger(j.left) && Number.isInteger(j.top));
  const dot = $("execDot"), vl = $("execVline"), hl = $("execHline");
  const sx = img.clientWidth / execShownW;
  const sy = img.clientHeight / execShownH;
  const show = (el, on) => { if(el) el.style.display = on ? "block" : "none"; };
  show(vl, clickable); show(hl, clickable); show(dot, clickable);
  if(clickable && dot && vl && hl){
    const x = (j.left + 0.5) * sx;
    const y = (j.top + 0.5) * sy;
    dot.style.left = x + "px";
    dot.style.top = y + "px";
    vl.style.left = x + "px";
    hl.style.top = y + "px";
  }
}

/* ---- 触发执行（发送鼠标点击） ---- */
async function execActNow(){
  const b = $("execAct");
  if(!b || b.disabled || execActBusy) return;
  execActBusy = true;
  b.disabled = true;
  b.textContent = "正在发送点击…";
  const j = await execGet("/api/execute/act", { method:"POST" });
  execActBusy = false;
  b.disabled = false;
  if(!j){ toast("执行请求失败（后端不可用）", "err"); }
  else if(j.ok){ toast("已执行：" + j.message, "ok"); }
  else { toast("无法执行：" + (j.message || "未知原因"), "err"); }
  renderExecActBtn(execLatest || { recognized:false }, false);
  await execLoadLatest();       // 同步展示当前结果（点击不触发新识别，画面保持原样供核对）
}

/* ---- 模式切换 / 轮询入口（单次识别，无后台循环） ---- */
function execOnModeChange(){
  if(appMode !== "exec") return;
  execPending = true;   // 进入即自动识别一轮：在途期间画面区保持转圈，避免先带回的占位快照把转圈换成误导文案
  execSyncStatus().then(async () => {
    const shown = await execLoadLatest();      // 先展示最近一帧画面（若有），避免空窗
    if(!shown) execShowScanning();             // 无历史画面：显示「正在截图识别…」，等待下方首次自动识别返回
    await execRefreshOnce();                   // 进入执行模式即自动截图识别一次（已有实现）
    setTimeout(execFitImage, 80);
  });
}

function execPollTick(){
  if(appMode !== "exec" || document.hidden) return;
  execLoadLatest();               // 同步最新快照（本次页面 / 其它窗口操作产生的结果）
}

/* ---- 自动识别（红色测试按钮）：连续循环 = 截图识别 → 显示结果并等 3 秒确认 →
       按下方所选前台/后台方式动作 → 等 3 秒游戏响应 → 下一轮 ---- */
const execSleep = ms => new Promise(r => setTimeout(r, ms));
const execModeZh = m => (m === "screen" ? "前台点击" : "后台消息");

function execAutoStatus(html){
  const el = $("execAutoState");
  if(!el) return;
  if(html){ el.className = "execAutoState show"; el.innerHTML = html; }
  else { el.className = "execAutoState"; el.innerHTML = ""; }
}
function execAutoBtnUi(){
  const b = $("execAutoBtn"); if(!b) return;
  b.textContent = execAutoOn ? "自动识别中（点击停止）" : "开启自动识别";
  b.classList.toggle("running", execAutoOn);
}
function execIsClickable(){
  return !!(execLatest && execLatest.recognized && execLatest.action === "click"
      && Number.isInteger(execLatest.left) && Number.isInteger(execLatest.top));
}
/* 循环期间禁用手动「立即识别 / 执行动作」，停止后按最新结果恢复 */
function execAutoSetManual(locked){
  const r = $("execRefresh"); if(r) r.disabled = locked;
  renderExecActBtn(execLatest || { recognized:false }, !locked && execIsClickable());
  if(locked){ const s = $("execSaveCap"); if(s) s.disabled = true; }
  else renderExecSaveCap(execLatest);     // 停止自动循环后按当前画面恢复可用态
}
function execAutoStop(){
  if(!execAutoOn) return;
  execAutoOn = false;
  execAutoSeq++;                       // 让仍在途的旧轮 await 返回后自弃退出
  execAutoSetManual(false);
  execAutoBtnUi();
  execAutoStatus("<b>已停止自动识别</b>（画面与右侧结果保留）。");
}
function execAutoToggle(){
  if(execAutoOn){ execAutoStop(); return; }
  execAutoOn = true;
  execAutoSeq++;
  execAutoBtnUi();
  execAutoSetManual(true);             // 循环期间禁用手动操作，避免与自动点击抢跑
  execAutoStatus("自动识别已开启，开始第 1 轮：正在截图识别…");
  execAutoLoop();
}
/* 倒计时等待：把模板里的 {s} 每秒替换成剩余秒数；等待期间被停止则返回 false */
async function execAutoWait(tpl, secs, seq){
  for(let i = secs; i >= 1; i--){
    if(!(execAutoOn && seq === execAutoSeq)) return false;
    execAutoStatus(tpl.split("{s}").join(String(i)));
    await execSleep(1000);
  }
  return execAutoOn && seq === execAutoSeq;
}
async function execAutoLoop(){
  const seq = execAutoSeq;
  let round = 0;
  while(execAutoOn && seq === execAutoSeq){
    round++;
    // 1) 截图并识别：/refresh 为同步一轮，返回即「识别完成」，随后渲染画面与右侧结果
    execAutoStatus("第 " + round + " 轮：正在截图识别…");
    const j = await execGet("/api/execute/refresh", { method:"POST" });
    if(!(execAutoOn && seq === execAutoSeq)) return;
    if(!j){
      const k1 = await execAutoWait('第 ' + round + ' 轮：识别接口不可用。<b>{s} 秒后重试…</b>', 3, seq);
      if(!k1) return;
      continue;
    }
    execLatest = j;
    renderExecAll();
    if(!execIsClickable()){
      // 未识别 / 该分类未定义点击动作：确认时间后直接下一轮（没有动作就没有“游戏响应”等待）
      const why = (j.imageWidth <= 0 && j.error)
          ? execEsc(j.error)
          : (j.state
              ? (j.action === "click"
                  ? "识别为「" + execEsc(j.state) + "」但该分类尚无点击坐标，跳过动作"
                  : "识别为「" + execEsc(j.state) + "」但该分类无「鼠标点击」动作，跳过动作")
              : "未识别出已标注分类（可能尚无同尺寸样本），不动作");
      const k1 = await execAutoWait('第 ' + round + ' 轮：' + why + '。<b>{s} 秒后开始下一轮…</b>', 3, seq);
      if(!k1) return;
      continue;
    }
    // 2) 识别出可点击动作：留 3 秒确认时间（可查看画面/右侧结果，随时可点按钮停止）
    const st = execEsc(j.state || "");
    const keep2 = await execAutoWait('第 ' + round + ' 轮：识别为「' + st + '」· 点击 (' + j.left + ',' + j.top + ')。'
        + '<b>{s} 秒后按「' + execModeZh(execClickMode) + '」执行…</b>', 3, seq);
    if(!keep2) return;
    // 3) 按所选前台 / 后台方式直接执行本轮已识别结果（后端不再重复截图识别）
    execAutoStatus('第 ' + round + ' 轮：正在按「' + execModeZh(execClickMode) + '」执行点击…');
    const r = await execGet("/api/execute/act", { method:"POST" });
    await execLoadLatest();            // 同步展示最近结果（点击不产生新识别）
    if(!(execAutoOn && seq === execAutoSeq)) return;
    if(!r){
      const k3 = await execAutoWait('第 ' + round + ' 轮：执行请求失败（后端不可用）。<b>{s} 秒后开始下一轮…</b>', 2, seq);
      if(!k3) return;
    } else if(r.ok){
      // 4) 动作完成：留 3 秒游戏响应时间再拍下一张
      const k3 = await execAutoWait('已执行点击（' + execModeZh(execClickMode) + '，分类「'
          + execEsc(r.state || st) + '」）。<b>{s} 秒游戏响应等待后开始下一轮…</b>', 3, seq);
      if(!k3) return;
    } else {
      const k3 = await execAutoWait('第 ' + round + ' 轮：本轮未能执行点击'
          + (r.message ? "（" + execEsc(r.message) + "）" : "") + '。<b>{s} 秒后开始下一轮…</b>', 2, seq);
      if(!k3) return;
    }
  }
}

/* ---- 右侧信息栏宽度：拖拽分隔条调节（双击复位），宽度持久化到 localStorage ---- */
(function execSideResize(){
  const split = $("execSplit"), side = $("execSide");
  if(!split || !side) return;
  const KEY = "mca.execSideW", DEF = 380, MIN = 300;
  const maxW = () => Math.max(MIN, Math.round(window.innerWidth * 0.65));
  let sideW = DEF;
  try{
    const v = parseInt(localStorage.getItem(KEY) || "", 10);
    if(v && v >= MIN && v <= maxW()) sideW = v;
  }catch(e){}
  side.style.width = sideW + "px";
  function apply(w){
    sideW = Math.max(MIN, Math.min(maxW(), Math.round(w)));
    side.style.width = sideW + "px";
    try{ localStorage.setItem(KEY, String(sideW)); }catch(e){}
    if(typeof execFitImage === "function") execFitImage();
  }
  split.addEventListener("mousedown", ev => {
    if(window.innerWidth <= 1000) return;   // 窄屏上下堆叠时不支持横向拖拽
    ev.preventDefault();
    const startX = ev.clientX, startW = side.getBoundingClientRect().width;
    split.classList.add("dragging");
    document.body.classList.add("split-dragging");
    let queued = 0;
    const move = e => {
      if(queued) return;
      queued = requestAnimationFrame(() => { queued = 0; apply(startW + (e.clientX - startX)); });
    };
    const up = () => {
      if(queued){ cancelAnimationFrame(queued); queued = 0; }
      document.removeEventListener("mousemove", move);
      document.removeEventListener("mouseup", up);
      split.classList.remove("dragging");
      document.body.classList.remove("split-dragging");
    };
    document.addEventListener("mousemove", move);
    document.addEventListener("mouseup", up);
  });
  split.addEventListener("dblclick", () => apply(DEF));
})();

/* ---- 标注模式右侧编辑区宽度：拖编辑面板左缘竖条调节（双击复位），宽度持久化 localStorage ---- */
(function markSideResize(){
  const split = $("markSplit");
  const ed = document.querySelector("aside.editor");
  if(!split || !ed) return;
  const KEY = "mca.editorW", DEF = 400, MIN = 300;
  const maxW = () => Math.max(MIN, Math.round(window.innerWidth * 0.5));
  let w = DEF;
  try{
    const v = parseInt(localStorage.getItem(KEY) || "", 10);
    if(v && v >= MIN && v <= maxW()) w = v;
  }catch(e){}
  ed.style.width = w + "px";
  function apply(nw){
    w = Math.max(MIN, Math.min(maxW(), Math.round(nw)));
    ed.style.width = w + "px";
    try{ localStorage.setItem(KEY, String(w)); }catch(e){}
    if($("imgwrap").style.display !== "none" && typeof applyMainZoom === "function") applyMainZoom();
    if(typeof renderDot === "function") renderDot();
  }
  split.addEventListener("mousedown", ev => {
    ev.preventDefault();
    const startX = ev.clientX, startW = ed.getBoundingClientRect().width;
    split.classList.add("dragging");
    document.body.classList.add("split-dragging");
    let queued = 0;
    const move = e => {
      if(queued) return;
      queued = requestAnimationFrame(() => { queued = 0; apply(startW - (e.clientX - startX)); });
    };
    const up = () => {
      if(queued){ cancelAnimationFrame(queued); queued = 0; }
      document.removeEventListener("mousemove", move);
      document.removeEventListener("mouseup", up);
      split.classList.remove("dragging");
      document.body.classList.remove("split-dragging");
    };
    document.addEventListener("mousemove", move);
    document.addEventListener("mouseup", up);
  });
  split.addEventListener("dblclick", () => apply(DEF));
})();

/* ---- 控件绑定与启动 ---- */
(function execBoot(){
  const r = $("execRefresh"), a = $("execAct"), ab = $("execAutoBtn"), img = $("execImg");
  if(r) r.addEventListener("click", execRefreshOnce);
  if(a) a.addEventListener("click", execActNow);
  if(ab) ab.addEventListener("click", execAutoToggle);
  const sc = $("execSaveCap"); if(sc) sc.addEventListener("click", execSaveToCapture);
  document.querySelectorAll('input[name="execModeOpt"]').forEach(mode => mode.addEventListener("change", async () => {
    if(!mode.checked) return;
    const j = await execGet("/api/execute/click-mode", { method:"POST",
        headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ mode: mode.value }) });
    execApplyMode(j && j.clickMode ? j.clickMode : mode.value);
  }));
  if(img) img.addEventListener("click", () => {
    if(execShownAt) openLightbox("/api/execute/frame?at=" + execShownAt,
        "执行模式最新画面（" + execClock(execShownAt) + "）");
  });
  let rs = 0;
  window.addEventListener("resize", () => { clearTimeout(rs); rs = setTimeout(execFitImage, 120); });
  setInterval(execPollTick, execPollMs);
  execSyncStatus();     // 页面打开即同步后端点击模式配置
})();

/* ---------------- 启动 ---------------- */
loadList(null);
syncCapStatus();
setInterval(pollTick, POLL_MS);
checkAppVersion();                    // 立即取一次基线
setInterval(checkAppVersion, META_MS);