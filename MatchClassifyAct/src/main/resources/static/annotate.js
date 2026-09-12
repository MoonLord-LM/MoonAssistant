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
/* 主图缩放偏好持久化键（localStorage）：选过的「原始分辨率 / 自适应缩放」跨 全部·已标注·未标注 视图、换图与刷新都保持 */
const ZOOM_KEY = "imgMainZoomMode";
let mainMode = (()=>{ try{ return localStorage.getItem(ZOOM_KEY) === "orig" ? "orig" : "fit"; }catch(e){ return "fit"; } })();   // 主图显示模式：fit=自适应缩放（整幅可见并尽量占满：不足等比缩小、充足等比放大，不产生滚动条） / orig=原始分辨率 1:1
let actionSel = "none";
let px = null;         // 点击点 {x,y}（图片像素）：仅 click 分类需要，执行模式鼠标真正点击的屏幕位置（点击区交集图框心）
let apx = null;        // 注意点 {x,y}：注意区交集图与匹配裁剪中心；未设 = 默认屏幕中心（全部分类一致，不回退点击点）
let pMode = "click";   // 图上编辑目标：click=红点（鼠标点击点）/ attn=绿点（关注点）；无动作分类固定编辑绿点
let attnUse = false;   // 「注意」行单选：true=关注点（单独指定了绿点）/ false=无需额外注意（未单独指定，按屏幕中心）
let ptTouch = { click:false, attn:false };   // 本图本轮是否被用户亲手改过红点/绿点（含「注意」行切到关注点）；防止屏幕中心兜底值被误判成“要重定义分类”
let loading = null;    // 当前图片名，用于防异步竞态
let lastMark = null;   // 上次输入/保存的标记草稿 {state,action,left,top,attnLeft,attnTop}，切到未标注图时自动带入
let baseMark = null;   // 当前图进入编辑区时的基准（文件已保存值 / 自动带入草稿）：「取消修改」按它还原
let DEF = {};          // 分类定义表快照 {state:{action,left,top,attnLeft,attnTop}}，来自 /api/annotate/defs（中心表：动作+点击点+注意点每分类一份）
let stateFilter = null;   // 分类过滤状态：作用于「全部 / 已标注」视图；null=不过滤，字符串=只显示该分类
let imgActFil = null;     // 截图动作过滤：作用于「全部 / 已标注」视图；null=全部，none/click=只看该动作

/* noAct=true 时不叠加动作过滤（仅作“当前分类/视图总数”展示用） */
function listNow(noAct){
  let L = FILTER === "all" ? ALL.slice() : ALL.filter(i => FILTER === "unmarked" ? !i.marked : i.marked);
  if(stateFilter && (FILTER === "all" || FILTER === "marked")){
    L = L.filter(i => i.marked && i.state === stateFilter);   // 「已标注」视图也可按分类过滤
  }
  if(!noAct && imgActFil && (FILTER === "all" || FILTER === "marked")){
    L = L.filter(i => i.marked && i.action === imgActFil);   // 截图视图也可按动作过滤（未标注图无动作，一并滤掉）
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
        top: typeof d.top === "number" ? d.top : null,
        attnLeft: typeof d.attnLeft === "number" ? d.attnLeft : null,
        attnTop: typeof d.attnTop === "number" ? d.attnTop : null
      };
    }
  }
  return m;
}
/* 某分类是否已有统一定义（动作 click 时的点击点、无动作时的注意点是否可直接采用） */
function defOf(state){ return DEF[state] || null; }
/* 两点是否相同（null / 缺省视为同一“未设”，即按屏幕中心） */
function samePt(al, at, bl, bt){
  return (al ?? null) === (bl ?? null) && (at ?? null) === (bt ?? null);
}
/* 提交内容与中心表已有定义是否逐项一致（动作 + 鼠标点击点 + 关注点） */
function sameDef(def, b){
  if(!def) return false;
  if(((def.action) || "none") !== ((b && b.action) || "none")) return false;
  return samePt(def.left, def.top, b.left, b.top) && samePt(def.attnLeft, def.attnTop, b.attnLeft, b.attnTop);
}
/* 定义/提交内容的中文描述，供重定义确认框展示 */
function defDesc(m){
  const act = (m && m.action) || "none";
  const cl = (m && m.left != null && m.top != null) ? m.left + "," + m.top : null;
  const at = (m && m.attnLeft != null && m.attnTop != null) ? m.attnLeft + "," + m.attnTop : null;
  return act === "click"
    ? "鼠标点击点(" + (cl || "中心") + ") · 关注点" + (at ? "(" + at + ")" : "默认屏幕中心")
    : "关注点(" + (at || "屏幕中心") + ")";
}

/* ---------------- 提示 ---------------- */
/* 历史日志：右下角出现过的消息全量保存到内存 LOG（弹窗回溯查看，与淡出展示互不影响）。
   普通消息在 toast()、截图/去重等在 showShotTip() 内入库（相同文本不重复）；
   批量任务的中间过程改为右栏 vtBar 进度条展示、不再逐步入库（只留开始 / 完成 / 失败等首尾 toast）；
   taskTip 仅作无右栏场景的进度兜底展示，默认不写日志。
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
/* 毫秒耗时 → “，耗时 Xs / Xm Ys”后缀（去重清理、特征验证等后端 costMs 通用展示；0/缺失返回空串） */
function fmtCostSuffix(ms){
  if(!ms || ms <= 0) return "";
  const sec = ms / 1000;
  if(ms < 60000){
    return "，耗时 " + (sec >= 1 ? sec.toFixed(1).replace(/\.0$/, "") + "s" : ms + "ms");
  }
  const m = Math.floor(sec / 60), s = Math.round(sec % 60);
  return "，耗时 " + m + "m " + (s < 10 ? "0" : "") + s + "s";
}
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
        '<button type="button" class="btn logclr" id="logCopy" title="复制全部历史日志文本到剪贴板">复制</button>' +
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
  const cpy = $("logCopy");
  if(cpy) cpy.addEventListener("click", async ()=>{
    const text = LOG.map(it => fmtLogTime(it.at) + " [" + logKindOf(it.kind) + "] " + it.txt).join("\n");
    if(!text){ toast("历史日志为空，没有可复制内容。", ""); return; }
    let ok = false;
    try{ await navigator.clipboard.writeText(text); ok = true; }
    catch(e){                                    // 非安全上下文 / 权限被拒时退回 textarea + execCommand
      const ta = document.createElement("textarea");
      ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta); ta.select();
      try{ ok = document.execCommand("copy"); }catch(e2){}
      ta.remove();
    }
    toast(ok ? "已复制 " + LOG.length + " 条历史日志到剪贴板。" : "复制失败，请手动框选复制。", ok ? "ok" : "err");
    refreshTotal();                              // 上面的提示也计入 LOG，同步条数
  });
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
   新进度直接替换旧内容，任务结束才淡出；默认把进度文本变化写入历史日志（相同文本不重复入库，
   避免一次任务几十条同文刷屏）。任务的中间过程已改为主视图右侧 vtBar 进度条展示、不再逐步入库，
   此处仅在用户退出「汇总分析」等无右栏场景作进度兜底，调用时传 noLog=true 只显示不写日志；
   任务的开始 / 完成 / 失败仍由 toast() 正常入库记录 */
let taskTipTimer = 0;
let lastTaskTipLog = "";        // 上一次已写入历史日志的任务提示文本
/* holdMs：本条驻留时长（默认 1600ms，够到下一次 2 秒轮询；由 1 秒 ticker 反复刷新的常驻进度传更大值，避免两次刷新之间闪掉） */
function taskTip(msg, kind, noLog, holdMs){
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
  if(!noLog && msg !== lastTaskTipLog){          // 文本变化 → 记入历史日志
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
  }, holdMs > 0 ? holdMs : 1600);
}

/* 启动历史重复清理进行态：与批量任务同款「一直刷新的进度消息」（含逐秒走动的已耗时）。
   后端在清理线程里逐张更新快照、meta 每 2 秒取一份；这里再挂 1 秒 ticker 复现，
   既避免两次轮询之间消息闪掉，也让「已耗时 N 秒」逐秒走动。进度文本变化频繁故 noLog
   （开始 / 结束各一条消息由 showShotTip 入库）。 */
/* 启动去重判定量文案：「已比对 N 次 · 复用 M 次」——compared = 本次真的读像素逐点比过的次数，
   reused = 按「文件名 + 最后修改时间」命中 dedup-cache.json 上次结果、没再比像素的次数；
   两个都为 0（还没比到任何一对同尺寸图）时返回空串，由 progLine 省略该段 */
function dedupCmpTxt(compared, reused){
  const parts = [];
  if(compared > 0) parts.push("已比对 " + compared + " 次");
  if(reused > 0) parts.push("复用 " + reused + " 次");
  return parts.join(" · ");
}
function dedupProgText(p){
  const t = Number(p.total) || 0, n = Number(p.done) || 0;
  const age = Math.max(0, Math.round((Date.now() - (Number(p.at) || Date.now())) / 1000));
  const cmp = dedupCmpTxt(Number(p.compared) || 0, Number(p.reused) || 0);
  if(!t && !n) return progLine("枚举历史截图", 0, 0, "", "", durTxt(age), cmp);   // 还没算出总数
  return progLine("检查重复图片", n, t, "张", p.current, durTxt(age), cmp);
}
function dedupProgTick(){
  if(!dedupProg) return;
  taskTip(dedupProgText(dedupProg), "", true, 3000);   // noLog：进度只显示不入库（避免每张一条刷屏）
}

/* ---------------- 列表加载 / 渲染 ---------------- */
async function loadList(preferName){
  let arr;
  try{ arr = await fetchAllSafe(); }catch(e){ toast("列表加载失败：" + e.message, "err"); return; }
  ALL = arr;
  try{ DEF = await fetchDefs(); }catch(_){ /* 列表优先，定义表下次刷新再试 */ }
  rebuildStates();
  const L = listNow();
  // 当前视图可见列表：未标注视图只含未打标截图
  let target = null;
  if(preferName && L.some(i => i.name === preferName)) target = preferName;
  // 视图内无偏好项才取视图首项
  if(!target && L.length) target = L[0].name;
  // 本视图无内容则只显示空态，不回退到其他视图的图
  if(!target){ renderList(); showEmpty(); return; }
  selectTarget(target);
}

/* ---------------- 分类标签 chip：未标注 / 已标注 编辑区与「全部」视图分类列共用同一结构、样式与交互 ----------------
   一个 chip 分三个可点区域：✎ = 整体重命名；数字 = 按该分类过滤左列表
   （「全部 / 已标注」过滤均在本视图内进行；「未标注」点数字转「全部」查看）；文本 = 「全部」视图按分类过滤 / 未标注、已标注“选中作为设定”。
   高亮：on（绿）= 文本已被选为当前分类标注；fil（蓝）= 列表正按该分类过滤。 */
function makeTagChip(box, state, count, o){
  const t = document.createElement("span");
  t.className = "tag";
  if(o && o.sel) t.classList.add("on");
  if(o && o.fil) t.classList.add("fil");
  const txt = document.createElement("span");
  txt.className = "txt";
  txt.textContent = state;
  txt.title = FILTER === "all"
    ? "让左侧只显示「" + state + "」分类的截图（再点一次取消过滤；与点数字一致）"
    : "把当前图片的分类标注设为「" + state + "」（自动带出该分类统一的动作与关注点坐标）";
  const n = document.createElement("span");
  n.className = "cnt";
  n.textContent = count;
  n.title = count + " 张已标注截图在使用该分类；点此让左侧列表只显示该分类";
  t.appendChild(txt);
  t.appendChild(n);
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
  txt.addEventListener("click", ()=> {
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

/* ---------------- 分类 chip / 智能建议一键填入：联动动作与关注点坐标 ----------------
   只把文本写进输入框会让“该分类已统一为动作”的情况无法直接保存（还差关注点坐标）。
   因此填入分类标注时，从该分类已标注样本（排除当前图）自动带入：
   动作取样本中占多数的动作；关注点坐标取该分类样本中出现次数最多的点
   （同一分类截图同窗口尺寸、画面一致，点击点/关注区域应固定，个别历史异位点不会带偏）。
   尚无任何样本的分类只填文本、保持当前动作选择（首次标注时的动作与默认屏幕中心在保存时才固定）。
   自动带入后仍可在图上单击微调坐标（click=红点 / 无动作=绿点）。 */
function categoryProbe(state, excludeName){
  const list = ALL.filter(i => i.marked && i.state === state && i.name !== excludeName);
  if(!list.length) return null;
  const cntA = new Map();
  for(const i of list){ const a = i.action || "none"; cntA.set(a, (cntA.get(a)||0) + 1); }
  const action = [...cntA.entries()].reduce((a,b)=> (b[1] > a[1] ? b : a))[0];
  const major = (keyOf)=> {
    const cnt = new Map();
    for(const i of list){
      if(i.action !== action) continue;
      const p = keyOf(i);
      if(!p) continue;
      const k = p.x + "," + p.y;
      cnt.set(k, (cnt.get(k)||0) + 1);
    }
    if(!cnt.size) return null;
    const best = [...cnt.entries()].reduce((a,b)=> (b[1] > a[1] ? b : a))[0].split(",");
    return { x:Number(best[0]), y:Number(best[1]) };
  };
  return {
    action,
    // 鼠标点击点：仅 click 分类的样本带（left/top）
    click: action === "click"
      ? major(i => (typeof i.left === "number" && typeof i.top === "number") ? {x:i.left, y:i.top} : null)
      : null,
    // 注意点：所有分类的样本都带（attnLeft/attnTop；旧版 none 单点已迁移为 attn）
    attn: major(i => (typeof i.attnLeft === "number" && typeof i.attnTop === "number")
      ? {x:i.attnLeft, y:i.attnTop}
      : ((action === "none" && typeof i.left === "number" && typeof i.top === "number")
        ? {x:i.left, y:i.top} : null))
  };
}

function adoptCategory(state){
  const sameState = $("stateInput").value.trim() === state;
  $("stateInput").value = state;
  let changed = !sameState;
  // 优先取同分类样本众数；样本缺失（或正在编辑本图）时回退中心表定义
  const def = defOf(state);
  const probe = categoryProbe(state, curName) ||
    (def ? {
      action: def.action,
      click: def.action === "click" && def.left != null && def.top != null ? {x:def.left, y:def.top} : null,
      attn: def.attnLeft != null && def.attnTop != null ? {x:def.attnLeft, y:def.attnTop}
        : (def.action === "none" && def.left != null && def.top != null ? {x:def.left, y:def.top} : null)
    } : null);
  if(probe){
    if(actionSel !== probe.action){ setAction(probe.action, false); changed = true; }
    const cwant = probe.action === "click" ? probe.click : null;   // 点击点：仅 click 分类
    const awant = probe.attn;                                       // 注意点：未设=null（默认屏幕中心）
    const sameC = cwant ? !!(px && px.x === cwant.x && px.y === cwant.y) : !px;
    const sameA = awant ? !!(apx && apx.x === awant.x && apx.y === awant.y) : !apx;
    if(!sameC){ px = cwant; changed = true; }
    if(!sameA){ apx = awant; changed = true; }
  }
  attnUse = !!apx;   // 跟随带入的草稿：有注意点 = 关注点，未设 = 无需额外注意
  ptTouch = { click:false, attn:false };   // 带入基准值 = 沿用，不算用户亲手改点
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
/* 返回冲突原因（null = 可以保存）。中心表已有该分类定义时一律放行——本次提交与定义不一致
   由 saveCurrent 走「重定义确认」（确认后按本次提交覆盖定义，全组样本同步）；
   仅“中心表无定义、但历史样本动作不统一”（定义表未加载 / 历史脏数据）时才兜底拦一下 */
function actionConflict(state, act, excludeName){
  if(!state) return null;
  const u = stateUsage(state, excludeName);
  if(u.count > 0 && !u.actions.has(act)){
    return "「" + state + "」已被 " + u.count + " 张图使用，匹配动作统一为「"
      + [...u.actions].map(actLabel).join(" / ") + "」；请改用该动作，或打开其中一张图改动作保存以重定义该分类。";
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
        msg = "本图属于「" + state + "」，把动作改为「" + actLabel(actionSel) + "」并保存会重定义该分类（动作/关注点全组同步，保存前会再次确认）。";
      } else if(def.action !== actionSel && !vacant){
        cls = "show info";
        msg = "「" + state + "」分类定义动作是「" + actLabel(def.action) + "」；本次保存会把它重定义为「" + actLabel(actionSel) + "」（动作/坐标对全组样本同步，保存前会再确认）。";
      } else if(def.action !== actionSel){
        cls = "show info";
        msg = "「" + state + "」旧定义是「" + actLabel(def.action) + "」但当前已无样本图，本次保存将把它重新定义为「" + actLabel(actionSel) + "」（需确定关注点坐标：未点选时默认屏幕中心）。";
      } else if(vacant){
        cls = "show ok";
        msg = "「" + state + "」定义沿用于「" + actLabel(def.action) + "」，本次保存补入本图样本。";
      } else {
        cls = "show ok";
        msg = "「" + state + "」已定义「" + actLabel(def.action) + "」，坐标沿用分类定义；在图上点按红/绿点后保存，会按你点的位置重定义该分类（全组样本同步，保存前会再确认）。";
      }
    } else if(u.count === 0){
      cls = "show info";
      msg = "「" + state + "」首次标注，所选动作与关注点坐标将固定为该分类的定义（关注点未点选时默认屏幕中心）。";
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
  const cnt = { all:nAll, unmarked:nUn, marked:nAll - nUn, think:keys.size, verify:vkCnt, opt:optCnt };
  const names = { all:"全部", unmarked:"未标注", marked:"已标注", think:"汇总分析", verify:"特征验证", opt:"算法调优" };
  for(const b of $("filterSeg").querySelectorAll("button")){
    b.classList.toggle("on", b.dataset.f === FILTER);
    const n = cnt[b.dataset.f];
    b.textContent = names[b.dataset.f] + (n == null ? "" : "（" + n + "）");
  }
}

/* 左栏两个过滤行按当前视图收敛显隐与高亮：动作过滤（#thinkFil：汇总分析/全部/已标注，读 thinkActFil / imgActFil）
   与验证结果过滤（#vkFil：仅特征验证，读 vkFil）。每次 renderList 都会走到，切视图时另一行自动隐藏。 */
function syncFilRows(){
  const el = $("thinkFil");
  const show = FILTER === "think" || FILTER === "all" || FILTER === "marked";
  el.hidden = !show;   // 直接赋值：原 `=== 比较再赋值` 恒为 no-op，过滤条不随视图显隐
  const cur = FILTER === "think" ? thinkActFil : (FILTER === "all" || FILTER === "marked" ? imgActFil : null);
  for(const b of el.querySelectorAll("button")) b.classList.toggle("on", b.dataset.a === cur);
  const vk = $("vkFil");
  const vkShow = FILTER === "verify";
  vk.hidden = !vkShow;
  for(const b of vk.querySelectorAll("button")) b.classList.toggle("on", b.dataset.v === vkFil);
}

function renderList(){
  syncFilRows();   // 过滤行（动作 / 验证结果）显隐与高亮跟随当前视图
  if(FILTER === "think"){ renderThinkList(); return; }
  if(FILTER === "verify"){ renderVerifyList(); return; }
  if(FILTER === "opt"){ renderOptList(); return; }
  const L = listNow();
  const actOn = imgActFil != null && (FILTER === "all" || FILTER === "marked");
  const ul = $("imgList"); ul.innerHTML = "";
  $("listCount").textContent = actOn ? L.length + " / " + listNow(true).length + " 张" : L.length + " 张";
  $("lstTitle").textContent = stateFilter && (FILTER === "all" || FILTER === "marked")
    ? "「" + stateFilter + "」分类" + (FILTER === "marked" ? "（已标注）" : "截图")
    : (FILTER === "unmarked" ? "截图列表（最旧在上）" : "截图列表（最新在上）");
  if(FILTER === "all") renderFilterPanel();   // 分类过滤面板跟随最新计数刷新
  const curItem = cur();
  for(const item of L){
    const li = document.createElement("li"); li.className="row" + (curItem && curItem.name===item.name ? " on" : "");
    const preview = item.marked
      ? (item.state || "(无分类标注)") + (item.action && ACT_LABEL[item.action] ? " ｜ " + (ACT_LABEL[item.action]||item.action) + (item.left!=null && item.top!=null ? `(${item.left},${item.top})` : "") : "")
      : "尚未标记";
    li.innerHTML =
      `<div class="r1"><span class="t">${SHORT(item.name)}</span><span class="chip ${item.marked?'m':'u'}">${item.marked?'已标记':'未标记'}</span></div>` +
      `<div class="r2">${preview.replace(/</g,"&lt;")}</div>`;
    li.addEventListener("click", ()=> changeTo(item.name));
    ul.appendChild(li);
  }
  if(!L.length){
    const d=document.createElement("li"); d.className="empty";
    const actTxt = imgActFil ? "「" + (ACT_LABEL[imgActFil] || imgActFil) + "」动作的" : "";
    // 目录里根本没有图片 → 统一说「没有图片」；有图片时才按当前筛选给具体提示
    d.textContent = ALL.length === 0 ? "没有图片"
      : FILTER === "marked" ? (stateFilter ? "「" + stateFilter + "」分类下暂无已标注截图"
          : actTxt ? "没有" + actTxt + "已标注截图" : "还没有已标记的图片")
      : FILTER === "all" && (stateFilter || imgActFil) ? (stateFilter ? "「" + stateFilter + "」分类下暂无截图" : "没有" + actTxt + "截图")
      : "🎉 全部图片都已标记";
    ul.appendChild(d);
  }
  setSegState();   // 筛选按钮文案 + 选中态一起刷新
  refreshJumpBar();   // 「全部」视图底部「修改这张图」入口随当前图/选中态刷新
}

/* ---------------- 「全部」视图：分类标签（点文本或数字 = 按该分类过滤，再点一次取消；✎=重命名） ---------------- */
function renderFilterPanel(){
  const box = $("catList"); if(!box) return;
  const counts = new Map();
  for(const i of ALL){ if(i.marked && i.state) counts.set(i.state, (counts.get(i.state)||0)+1); }
  box.innerHTML = "";
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
  if(f === "think"){ if(FILTER === "verify") exitVerify(); else if(FILTER === "opt") exitOpt(); enterThink(); return; }   // 汇总分析入口自带 refreshThink 全量刷新
  if(f === "verify"){ if(FILTER === "think") exitThink(); else if(FILTER === "opt") exitOpt(); enterVerify(); return; }    // 特征验证：左栏算法列表 + 主区 A/B 分值明细
  if(f === "opt"){ if(FILTER === "think") exitThink(); else if(FILTER === "verify") exitVerify(); enterOpt(); return; }     // 算法调优：特征组合成匹配算法并验证分类准确率
  if(FILTER === "think"){ exitThink(); curName = null; }
  else if(FILTER === "verify"){ exitVerify(); curName = null; }
  else if(FILTER === "opt"){ exitOpt(); curName = null; }
  FILTER = f;
  if(f !== "all") stateFilter = null;        // 分类过滤只属于「全部」视图，离开即重置
  if(f === "unmarked") imgActFil = null;     // 动作过滤只作用于已标注截图（全部/已标注），未标注视图复位
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
  $("edVerify").style.display = FILTER === "verify" ? "" : "none";
  $("edOpt").style.display = FILTER === "opt" ? "" : "none";
  $("edNorm").style.display = (FILTER === "unmarked" || FILTER === "marked") ? "" : "none";
  $("edFilter").style.display = FILTER === "all" ? "" : "none";
  $("edJump").style.display = FILTER === "all" ? "" : "none";
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
  btn.textContent = item.marked ? "修改此图（Enter）" : "标注此图（未标注视图）";
  btn.title = item.marked
    ? "跳转到「已标注」视图并定位这张图（快捷键 Enter），可直接改分类标注 / 匹配动作 / 关注点坐标后保存"
    : "跳转到「未标注」视图并定位这张图（快捷键 Enter），可补全分类标注 / 匹配动作 / 关注点坐标后保存";
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
  imgActFil = null;                   // 动作过滤同理：跳到单图编辑视图即复位（避免定位目标被过滤隐藏）
  rebuildStates();                    // 标注视图：chips 的 sel/fil 随视图重置
  syncSugDock();
  syncRightPanel();                   // 右栏：分类过滤 → 标注编辑
  selectTarget(item.name);            // 定位到当前这张图并载入标注，可直接点图改坐标
  refreshSmartTip();
  toast(item.marked
    ? "已跳到「已标注」视图：可直接修改此图的分类标注 / 匹配动作 / 关注点坐标（在图上点一下可微调）"
    : "已跳到「未标注」视图：可直接补全此图的分类标注 / 匹配动作 / 关注点坐标", "ok");
  return true;
}

function showEmpty(msg){
  curName = null;
  $("imgwrap").style.display = "none";
  $("placeholder").style.display = "";
  // 「未标注」列表为空（全部已标注 / 尚无图）→ 中区提示文字下加「手动采集」入口；其余视图不显示
  const unmarkedEmpty = FILTER === "unmarked";
  $("imgarea").classList.toggle("emptycol", unmarkedEmpty);
  $("capManualBtn").style.display = unmarkedEmpty ? "" : "none";
  showZoomCtl(false);
  resetZoom();
  // 默认文案按当前视图给，调用方传 msg 时以其为准
  $("placeholder").textContent = msg || (ALL.length
    ? (FILTER === "unmarked"
      ? "点击 手动采集 或 自动采集，获取新的截图素材"
      : FILTER === "marked"
        ? "还没有已标注的截图：到「未标注」给截图打上分类标注后即会出现在这里。"
        : "当前视图下暂无可显示的截图。")
    : "没有图片。截图任务开启后，新截图会自动出现并同步到本列表。");
  $("fname").textContent = ""; $("fsub").textContent = ""; $("imgDims").textContent = "";
  $("stateInput").value = ""; setAction("none"); px=null; apx=null; attnUse=false;
  ptTouch = { click:false, attn:false };
  renderDot();
  baseMark = null;            // 无当前图：取消修改无可还原基准
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
  $("imgarea").classList.remove("emptycol");
  $("capManualBtn").style.display = "none";   // 有图展示 → 收起手动采集入口
  $("imgDims").textContent = "";   // 待图片加载完成后按实际分辨率填充
  naturalW = naturalH = 0;
  px = null; apx = null; attnUse = false;
  $("mainImg").style.pointerEvents = "none";   // 换图预解码期间锁定旧图：坐标取点/缩放以就绪的新图为准
  showZoomCtl(true);
  loadMainImg(imgUrl(item.name), item.name);   // 预解码就绪后同帧换源（期间旧图保持显示，不闪空白）；显示模式保持用户选择，不随换图复位
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
    px = null; apx = null; attnUse = false;
    renderDot();
    updateTagActive();
  }
  baseMark = editorDraft();   // 记录本图进入编辑区时的基准值，「取消修改」按它还原
}

/* 编辑区当前内容快照（分类标注 / 匹配动作 / 点击点 / 注意点）：作为取消修改的还原基准 */
function editorDraft(){
  return { state: $("stateInput").value, action: actionSel,
    left: actionSel === "click" && px ? px.x : null,
    top: actionSel === "click" && px ? px.y : null,
    attnLeft: apx ? apx.x : null, attnTop: apx ? apx.y : null };
}

$("mainImg").addEventListener("load", ()=>{
  const img = $("mainImg");
  img.style.pointerEvents = "";
  naturalW = img.naturalWidth || 0;
  naturalH = img.naturalHeight || 0;
  $("imgDims").innerHTML = naturalW && naturalH
    ? `尺寸 <b>${naturalW} × ${naturalH}</b> 像素`
    : "";
  resetZoom();             // 图片就绪：按当前缩放模式套用（自适应缩放 / 原始分辨率），换图不复位该设置
  // 两个点各自独立（屏幕中心是共同默认值）：click 分类点击点（红）必落中心；关注点（绿）未单独指定即不落值
  // （渲染与产物端都按屏幕中心解析）；无动作分类没有点击点
  if(naturalW && naturalH && FILTER !== "all"){
    if(actionSel === "click"){
      if(!px) px = centerPt();             // 点击点兜底默认屏幕中心
    }else{
      px = null;                           // 无动作分类没有点击点
    }
  }
  renderDot();
});
$("mainImg").addEventListener("error", ()=>{
  $("mainImg").style.pointerEvents = "";
  toast("图片加载失败：" + (cur()?cur().name:"") , "err");
});

/* ---------------- 主图缩放：原始分辨率 / 自适应缩放 ---------------- */
function showZoomCtl(show){ $("zoomCtl").classList.toggle("show", !!show); }

/* 回到自然尺寸（清掉按旧图套用的显式宽高与缩放态），换图/尚无尺寸时用 */
function clearImgSize(){
  const img = $("mainImg"), st = img.style, wrap = $("imgwrap");
  st.removeProperty("width"); st.removeProperty("height");
  st.removeProperty("maxWidth"); st.removeProperty("maxHeight");
  img.style.imageRendering = "auto";
  wrap.classList.remove("zoomed");
}

/* 主图换源：先由临时 Image 预解码，就绪后再同帧替换 <img> src——
   换图/切视图期间旧图保持显示到最后一刻，不闪空白、不回闪自然尺寸 */
function loadMainImg(url, expect){
  const img = new Image();
  const swap = ()=>{
    if(loading !== expect || curName !== expect) return;   // 期间已切走/清空：丢弃本次预解码结果
    naturalW = naturalH = 0;
    clearImgSize();                          // 新图先按自然尺寸渲染，尺寸与缩放由主图 load 事件按当前模式套用
    const main = $("mainImg");
    main.removeAttribute("src");
    main.src = url;
  };
  img.onload = swap;
  img.onerror = ()=>{ swap(); };             // 解码失败也切到主图 src，由主图 error 事件统一提示
  img.src = url;
}

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
  const s = mainScale();
  if(!s){                     // 尚无尺寸：先回到自然尺寸，待图片 load 后再按模式套用
    clearImgSize();
    syncZoomCtl();
    return;
  }
  const img = $("mainImg"), st = img.style, wrap = $("imgwrap");
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
  try{ localStorage.setItem(ZOOM_KEY, m); }catch(e){}   // 偏好持久化：跨视图 / 换图 / 刷新保持
  applyMainZoom();
}

/* 直接点图片：在「自适应缩放 ↔ 原始分辨率」之间切换（鼠标点击模式下用于取坐标） */
function toggleMainMode(){
  if(!naturalW) return;
  setMainMode(mainMode === "orig" ? "fit" : "orig");
}

function resetZoom(){
  // 只回左上并套用当前模式；缩放模式是用户偏好，不随换图 / 视图切换复位
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

/* ---------------- 动作 / 坐标（点击点·注意点双点） ---------------- */
function setAction(a, markDirty){
  if(a!=="click" && a!=="none") a = "none";        // 仅保留 无动作 / 鼠标点击 两种
  const oldAct = actionSel;
  actionSel = a;
  // 用户手动切换动作时的双点承接（markDirty=true 表示由点选动作触发；载入/带入走 applyBodyToEditor 再覆盖）：
  //   click→none：没有注意点就把原点击点转为注意点（画面仍关注同一位置）；click 点随之废弃
  //   none→click：没有点击点就把原注意点转为点击点（默认点同一位置）
  if(markDirty && oldAct !== a){
    if(a === "none"){ if(!apx && px){ apx = { x:px.x, y:px.y }; attnUse = true; } px = null; }
    else if(!px && apx){ px = { x:apx.x, y:apx.y }; }
  }
  document.body.dataset.mode = a;
  document.querySelectorAll(".act[data-a]").forEach(el=> el.classList.toggle("on", el.dataset.a===a));
  const radios = document.querySelectorAll('input[name=action]');
  for(const r of radios){ if(r.value===a) r.checked = true; }
  // 编辑目标：无动作分类永远只编辑绿点；click 分类由「鼠标点击 / 关注点」chip 决定（换动作不影响已选目标）
  if(a === "none") pMode = "attn";
  else if(pMode !== "click" && pMode !== "attn") pMode = "click";
  // 两个点各自独立：click 分类点击点=红、注意点=绿（未设 = 默认屏幕中心）；无动作分类只有注意点
  $("coordBox").style.display = FILTER !== "all" ? "block" : "none";
  renderDot();
  updateHints();
  if(markDirty){ setDirty(); }
}

/* 当前正在编辑的点（红点 / 绿点）：click 分类由 chip 决定，无动作分类只有绿点 */
function edPoint(){ return actionSel === "click" ? pMode : "attn"; }

/* 图片中心（屏幕中心）默认点；图未加载完成返回 null */
function centerPt(){
  return (naturalW && naturalH) ? { x: Math.floor(naturalW / 2), y: Math.floor(naturalH / 2) } : null;
}

/* 切换图上编辑目标（红点 / 绿点）：只切目标不改数据（无未保存提示） */
function setPMode(m, dirtyIt){
  if(m !== "click" && m !== "attn") return;
  if(actionSel !== "click") m = "attn";   // 无动作分类只编辑绿点
  pMode = m;
  renderDot();
  if(dirtyIt) setDirty();
}

/* 「注意」行单选：off = 无需额外注意（不单独指定关注点，注意区交集图 / 匹配裁剪按屏幕中心）；
   on = 关注点（绿点，未点图时默认屏幕中心、可点图改），选中即把图上编辑目标切到绿点 */
function setAttnUse(on, markDirty){
  const changed = attnUse !== on;
  attnUse = on;
  if(on){
    if(!apx) apx = centerPt();
    pMode = "attn";
  }else{
    apx = null;   // 未单独指定 = 产物与渲染都按屏幕中心解析
  }
  renderDot();
  if(markDirty && changed){ ptTouch.attn = true; setDirty(); }   // 亲手切「注意」行 = 明确要改关注点（on=点绿点 / off=按屏幕中心）
}

/* 两行单选的选中态与 body[data-pt]（十字辅助线配色、当前编辑点外环高亮）同步：
   动作行由 setAction 维护 .on；注意行由 attnUse 决定；坐标文本只在选中对应项时显示 */
function syncPtUI(){
  const pt = edPoint();
  document.body.dataset.pt = pt;
  const inEditor = FILTER !== "all";
  const rOff = $("attnUseOff"), rOn = $("attnUseOn");
  if(rOff) rOff.checked = !attnUse;
  if(rOn) rOn.checked = attnUse;
  document.querySelectorAll(".act[data-n]").forEach(el=>{
    el.classList.toggle("on", el.dataset.n === (attnUse ? "on" : "off"));
  });
  const cTxt = $("clickPtText"), aTxt = $("attnPtText");
  if(cTxt) cTxt.style.display = (inEditor && actionSel === "click") ? "" : "none";
  if(aTxt) aTxt.style.display = (inEditor && attnUse) ? "" : "none";
}

/* 把一组标记填入编辑区；markDirty=true 时标记为已修改（需保存）。
   click：left/top = 鼠标点击点（必填）；attnLeft/attnTop = 注意点（可选，未设 = 默认屏幕中心）。
   无动作：left/top 恒空，attnLeft/attnTop = 注意点。没有的等图片加载完成后自动落屏幕中心默认点 */
function applyBodyToEditor(b, markDirty){
  const state = (b && b.state) || "";
  const act = (b && b.action && ACT_LABEL[b.action]) ? b.action : "none";
  $("stateInput").value = state;
  setAction(act, false);
  if(act === "click"){
    if(b && typeof b.left === "number" && typeof b.top === "number"){ px = { x:b.left, y:b.top }; }
    else { px = null; }
  } else {
    px = null;   // 无动作分类没有点击点
  }
  if(b && typeof b.attnLeft === "number" && typeof b.attnTop === "number"){ apx = { x:b.attnLeft, y:b.attnTop }; }
  else { apx = null; }
  attnUse = !!apx;   // 「注意」行跟随载入值：有注意点 = 关注点，未设 = 无需额外注意
  ptTouch = { click:false, attn:false };   // 填入编辑区 = 新基准，用户触点从头计
  renderDot();
  updateTagActive();
  if(markDirty){ setDirty(); }
}

function toCss(p){
  if(!p || !naturalW) return null;
  const img = $("mainImg");
  return {
    x: (p.x + 0.5) * img.clientWidth  / naturalW,
    y: (p.y + 0.5) * img.clientHeight / naturalH
  };
}

/* 绘制图上两个点（红绿同时标出）：#dot 红 = 鼠标点击点（仅鼠标点击分类有）、#adot 绿 = 关注点
   （任意分类，未设 = 默认屏幕中心）；两点各带一套十字线（红点 = #vline/#hline、绿点 = #avline/#ahline）
   并始终同时展示，当前编辑目标那一套由 CSS body[data-pt] 加粗 + 点的外环高亮表示 */
function renderDot(){
  const dot = $("dot"), v = $("vline"), h = $("hline");
  const av = $("avline"), ah = $("ahline"), adot = $("adot");
  const inEditor = FILTER !== "all";
  const pt = edPoint();
  const edit = actionSel === "click" || actionSel === "none";
  const loaded = !!(naturalW && naturalH);
  const center = loaded ? { x: Math.floor(naturalW / 2), y: Math.floor(naturalH / 2) } : null;
  // 红点 = 点击点：仅鼠标点击分类有（无动作分类没有点击点）；未点过按默认屏幕中心绘出（保存时同样兜底中心）
  const cp = (inEditor && edit && actionSel === "click") ? (px || center) : null;
  // 绿点 = 注意点：任意分类都有，未设 = 默认屏幕中心（便于直接点图改成别的点）
  const ap = (inEditor && edit) ? (apx || center) : null;
  const cb = $("coordBox");
  if(cb) cb.style.display = inEditor && edit ? "block" : "none";
  dot.style.display = (loaded && cp) ? "block" : "none";
  if(adot) adot.style.display = (loaded && ap) ? "block" : "none";
  dot.classList.toggle("on", pt === "click");     // 当前编辑目标加外环高亮（点本身颜色恒定，不随目标切换变色）
  if(adot) adot.classList.toggle("on", pt !== "click");
  const cTxt = $("clickPtText");
  if(cTxt){
    cTxt.textContent = (!inEditor || actionSel !== "click") ? "—"
      : (px ? `（${px.x}, ${px.y}）` : "（默认屏幕中心）");
  }
  const aTxt = $("attnPtText");
  if(aTxt){
    aTxt.textContent = (!inEditor || !edit) ? "—"
      : (apx ? `（${apx.x}, ${apx.y}）` : "（默认屏幕中心）");
  }
  const put = (el, p)=>{ if(el && p){ const c = toCss(p); if(c){ el.style.left = c.x + "px"; el.style.top = c.y + "px"; } } };
  // 竖线只吃 left、横线只吃 top：两套线都常显（红点线恒红、绿点线恒绿），加粗交给 CSS body[data-pt]
  const line = (el, vertical, c)=>{
    if(!el) return;
    el.style.display = c ? "block" : "none";
    if(c){ el.style[vertical ? "left" : "top"] = (vertical ? c.x : c.y) + "px"; }
  };
  const cc = cp ? toCss(cp) : null;
  const ac = ap ? toCss(ap) : null;
  line(v, true, cc);   line(h, false, cc);
  line(av, true, ac);  line(ah, false, ac);
  put(dot, cp);
  if(adot) put(adot, ap);
  syncPtUI();
}

$("mainImg").addEventListener("click", (e)=>{
  // 标注视图（未标注 / 已标注）：图上点选当前编辑目标（点击点=红 / 注意点=绿，见数据条切点按钮）
  if((actionSel === "click" || actionSel === "none") && FILTER !== "all"){
    if(!naturalW){ toast("图片尚未加载完成", "err"); return; }
    const img = $("mainImg");
    const r = img.getBoundingClientRect();
    if(r.width<=0 || r.height<=0) return;
    const nx = Math.max(0, Math.min(naturalW-1, Math.round((e.clientX - r.left) / r.width * naturalW)));
    const ny = Math.max(0, Math.min(naturalH-1, Math.round((e.clientY - r.top) / r.height * naturalH)));
    if(edPoint() === "attn"){
      apx = { x:nx, y:ny };
      attnUse = true;   // 图上手动点绿点 = 明确指定关注点（「注意」行随之切到「关注点」）
      ptTouch.attn = true;
    } else {
      px = { x:nx, y:ny };
      ptTouch.click = true;
    }
    renderDot();
    setDirty();
    return;
  }
  // 「全部」浏览视图（不编辑标注）等：单击图片 = 在「自适应缩放 ↔ 原始分辨率」之间切换（自适应整幅可见，不足缩小、充足放大到占满可用区）
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
  // 点击点（click 必填）与注意点（无动作必填，click 可选）分开提交；
  // 图上没点自动取屏幕中心默认点（加载完成后已按中心画好，此处兜底）
  const body = { state, action: actionSel };
  if(!naturalW || !naturalH){
    toast("图片尚未加载完成，请稍候再保存", "err");
    return null;
  }
  const c = { x: Math.floor(naturalW / 2), y: Math.floor(naturalH / 2) };
  if(actionSel === "click"){
    if(!px){ px = { x:c.x, y:c.y }; renderDot(); }
    body.left = px.x; body.top = px.y;
  } else {
    px = null;   // 无动作分类不携带点击点
    if(!apx){ apx = { x:c.x, y:c.y }; renderDot(); }
  }
  if(apx){
    body.attnLeft = apx.x; body.attnTop = apx.y;
  }
  return body;
}

function setDirty(){ dirty = true; }

/* 标注编辑面板可用性：列表为空 / 全部标记完成 → 右侧按钮、输入与两行单选（动作 + 注意）连 chip 一并置灰，避免空操作 */
function setEditorEnabled(on){
  ["btnSaveNext","btnLast","btnClear","btnDelete","stateInput"].forEach(id => {
    const el = $(id); if(el) el.disabled = !on;
  });
  // 动作 + 注意两行单选都置灰（此前只禁了动作行，注意行仍可点，与置灰状态不一致）
  document.querySelectorAll('#coordBox input[type="radio"]').forEach(r => { r.disabled = !on; });
  // radio 置灰只影响圆点，chip 边框/文字/悬停要一并失效
  document.querySelectorAll('#coordBox .act').forEach(el => el.classList.toggle("dis", !on));
}

/* 保存后取当前列表视觉顺序的下一张（全部 / 已标注 = 最新在上；未标注 = 最旧在上）。
   未标注视图：保存后该图已移出列表，仍有剩余未标注就继续下一张，列表真空才提示全部标记完 */
function advanceAfterSave(item){
  const L = listNow();
  const p = L.findIndex(i => i.name === item.name);
  // p>=0 直接下一行；p<0 仅发生在未标注视图：优先找名字更晚的一张，
  // 已是最晚但仍剩更旧未标注时回到列表头，避免把“下方还有”误判成全部完成
  let np = p >= 0 ? p + 1 : L.findIndex(i => i.name > item.name);
  if(p < 0 && np < 0 && L.length) np = 0;
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
  // 该分类在中心表的已有定义 → 本次提交与它不一致 = 重定义该分类（动作/坐标对全组样本同步，保存前确认）。
  //   · 本图已属该分类：动作或任一点有变即重定义（原口径）；
  //   · 本图未标注 / 换了分类名：只有动了动作、或亲手点过红/绿点且与定义不同才算重定义——
  //     否则“只输入分类名、坐标由屏幕中心兜底”会被误判成重定义，把定义改成中心。
  const def = defOf(body.state);
  const sameState = !!(item.marked && item.state && item.state === body.state);
  const actDiff = !!def && ((def.action || "none") !== (body.action || "none"));
  const ptDiff = !!def && (
    (ptTouch.click && !samePt(def.left, def.top, body.left, body.top)) ||
    (ptTouch.attn && !samePt(def.attnLeft, def.attnTop, body.attnLeft, body.attnTop)));
  let redefine = sameState || actDiff || ptDiff;
  if(redefine){
    if(sameDef(def, body)){
      if(item.marked){   // 已标注图原样重存：动作与两点都没变，无需写盘
        toast("「" + body.state + "」未做改动，无需保存", "info");
        return;
      }
      redefine = false;  // 未标注图：坐标与已有定义一致，只登记分类归属（沿用定义）
    }else{
      const extra = item.marked ? "" : "\n本图尚未标注，保存后会归入「" + body.state + "」并转入「已标注」。";
      if(!confirm("「" + body.state + "」的分类定义当前为「" + defDesc(def || item) + "」。\n"
        + "本次保存将把它重新定义为「" + defDesc(body) + "」，并作为该分类全部样本的统一动作/鼠标点击点/关注点。"
        + extra + "\n\n继续？")) return;
      redefine = true;
    }
  }else{
    const conflict = actionConflict(body.state, body.action, item.name);
    if(conflict){ toast(conflict, "err"); updateHints(); return; }
  }
  try{
    // redefine=true 显式告知后端：本次已确认重定义，按提交内容覆盖该分类定义（未标注图/换分类名也可）
    const resp = await fetch(markUrl(item.name) + (redefine ? "?redefine=true" : ""), {
      method:"PUT", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body)
    });
    if(!resp.ok){ toast("保存失败：" + (await resp.text() || resp.status), "err"); return; }
    const saved = await resp.json();
    item.marked = true; item.state = saved.state || ""; item.action = saved.action || "none";
    item.left = (saved.action === "click") ? (saved.left ?? null) : null;
    item.top = (saved.action === "click") ? (saved.top ?? null) : null;
    item.attnLeft = saved.attnLeft ?? null; item.attnTop = saved.attnTop ?? null;
    // 定义可能被后端采用/重定义，以响应为准
    DEF[item.state] = {
      action: item.action || "none",
      left: item.left, top: item.top,
      attnLeft: item.attnLeft, attnTop: item.attnTop
    };
    lastMark = {
      state: saved.state || "", action: saved.action || "none",
      left: item.left, top: item.top,
      attnLeft: item.attnLeft, attnTop: item.attnTop
    };
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
    item.attnLeft=null; item.attnTop=null;
    dirty = false;
    $("stateInput").value=""; setAction("none", false); px=null; apx=null;
    ptTouch = { click:false, attn:false };
    renderDot();
    baseMark = editorDraft();   // 标记已清空：之后「取消修改」应还原为“无标注”而非清除前旧值
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
  dirty = false; px = null; apx = null; attnUse = false;
  ptTouch = { click:false, attn:false };
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

/* 「取消修改」：把分类标注 / 匹配动作 / 关注点还原为打开本图时的值（文件里已保存的标注，
   未标注图则还原为空初值），并清除未保存修改标记 */
function cancelCurrentMod(){
  const item = cur(); if(!item){ toast("没有可操作的图片", "err"); return; }
  if(!dirty){ toast("当前图没有未保存的修改", "info"); return; }
  applyBodyToEditor(baseMark || { state:"", action:"none", left:null, top:null, attnLeft:null, attnTop:null }, false);
  // 基准无点且图已加载 → 与图片加载逻辑一致：点击点兜底屏幕中心；关注点未单独指定即不落值（按屏幕中心解析）
  if(naturalW && naturalH && FILTER !== "all"){
    if(actionSel === "click"){ if(!px) px = centerPt(); }
    else { px = null; }
  }
  renderDot();
  dirty = false;
  updateHints();
  toast("已取消修改，还原为打开本图时的标注", "ok");
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

/* 汇总分析模式下 ↑/↓ 在当前动作过滤后的组合队列里移动 */
function navThinkStep(d){
  const L = thinkShown();
  if(!L.length){ toast("没有可浏览的分类标注"); return; }
  let p = L.findIndex(g => gkey(g) === selKey);
  if(p<0){ p = d>0 ? -1 : L.length; }
  const np = p + d;
  if(np<0 || np>=L.length){ toast(d<0 ? "已是第一组" : "已是最后一组"); return; }
  openGroup(L[np]);
  const li = $("imgList").children[np]; if(li) li.scrollIntoView({block:"nearest"});
}

/* 顶部上一张/下一张/刷新等按钮已移除（顶栏右上角保留「自动采集/暂停采集」与「完全退出」），保留空实现兼容既有调用点 */
function updateNavButtons(){}

/* ---------------- 自动采集/暂停采集（自动采集默认不开启，需在页面手动开启） ---------------- */
let capPaused = true;    // 截图任务是否未开启/已暂停（程序启动后默认关闭）
let capBusy = false;
let capIntervalMs = null;            // 后端真实截图间隔（毫秒），由 /api/capture/status 下发
let capDiffThreshold = null;         // 后端像素去重阈值（%），0 = 关闭去重
let lastStopReasonShown = null;   // 已弹窗提示过的自动暂停原因（去重，避免每 5s 轮询重复弹窗）
let lastDedupNoticeAt = -1;       // 已提示过的「启动重复清理结果」时间戳（去重：一条结果只在首次轮询到的那一刻右下角提示，每 2s 轮询不重复打扰）
let lastDedupProgAt = -1;         // 已提示过「开始检查」的那次启动清理（= 后端进行态快照的 at；换一次启动即换一个 at，重新提示）
let dedupProg = null;             // 启动重复清理的最近一份进行态快照；非空 = 正在扫描（由 1 秒 ticker 复现进度消息，让「已耗时」逐秒走动）

/* 毫秒间隔 → 人类可读文案（整千显示整秒，否则保留一位小数秒） */
function fmtCapInterval(ms){
  if(!ms || ms <= 0) return null;
  if(ms < 1000) return ms + " 毫秒";
  return (ms % 1000 === 0) ? (ms / 1000) + " 秒" : (ms / 1000).toFixed(1) + " 秒";
}

function renderCapBtn(){
  const b = $("btnCap"); if(!b) return;
  b.classList.toggle("on", !capPaused);        // 截图运行时按钮高亮为绿色
  b.textContent = capPaused ? "自动采集" : "暂停采集";
  b.disabled = capBusy;
  const per = fmtCapInterval(capIntervalMs);
  b.title = capPaused
    ? "截图未开启：点击后开始后台周期截图（原始截图保存到 capture/）"
    : "截图运行中（每 " + (per || "按配置间隔") + " 截取一帧" +
      (capDiffThreshold > 0 ? "，与每张已保存图不一致像素占比均 > " + capDiffThreshold + "% 才保存" : "") +
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
      msg = "已暂停自动采集：后台不再截取保存新图，控制台仍可正常使用";
    }else{
      const per = fmtCapInterval(capIntervalMs);
      if(capDiffThreshold > 0){
        msg = "已开启自动采集：每 " + (per || "按配置间隔") + " 截取一帧，" +
              "与每张已保存图不一致像素占比均 > " + capDiffThreshold + "% 才会保存为新图";
      }else{
        msg = "已开启自动采集：每 " + (per || "按配置间隔") + " 截取一帧并保存";
      }
    }
    toast(msg, "ok");
  }catch(e){
    toast("切换失败：" + e.message, "err");
  }
  capBusy = false; renderCapBtn();
}

/* 手动采集（「未标注」空列表中间按钮）：请求后端立即截一帧并做与执行模式同一套全尺寸逐像素去重
   （与 capture/、classify/ 全部同尺寸图比对，差异须 > 0.5% 手动阈值），通过即插入待标注列表并打开标注 */
async function capManualShot(){
  const b = $("capManualBtn");
  if(!b || b.disabled) return;
  // 百分比展示文本：整数直显、非整数保留两位去尾零（与截图事件流提示同一口径）
  const fmtPct = v => (Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100));
  b.disabled = true; b.textContent = "采集中…";
  try{
    const r = await fetch("/api/capture/manual", { method:"POST", cache:"no-store" });
    let j = null; try{ j = await r.json(); }catch(_){}
    if(!r.ok || !j) throw new Error("HTTP " + r.status);
    if(j.ok && j.name){
      // 通过去重：附上本次去重扫描中与任一已有截图的差异百分比（后端 minDiffPercent；无同尺寸参考不附）
      const md = (typeof j.minDiffPercent === "number" && j.minDiffPercent >= 0)
        ? "（与已有截图差异 " + fmtPct(j.minDiffPercent) + "%）" : "";
      toast("已手动采集并通过去重检查，插入待标注 → " + SHORT(j.name) + md, "ok");
      if(FILTER === "unmarked"){
        await loadList(j.name);   // 仍在未标注视图：刷新列表并定位到新截图，直接进入标注编辑
      }else{
        await refreshSilent();    // 采集期间已切走：只把新图同步进列表，不打扰当前视图
      }
    }else{
      const msg = j.message || (j.kind === "dup"
        ? "画面与已保存截图重复（差异 "
          + (typeof j.diffPercent === "number" ? fmtPct(j.diffPercent) : "?") + "% ≤ "
          + (typeof j.threshold === "number" ? fmtPct(j.threshold) : "?") + "% 阈值），本次未保存。"
        : "手动采集失败（" + (j.kind || "unknown") + "），请稍后重试。");
      toast(msg, (j.kind === "dup" || j.kind === "busy") ? "skip" : "err");
    }
  }catch(e){
    toast("手动采集失败：" + e.message, "err");
  }finally{
    if(b){ b.disabled = false; b.textContent = "手动采集"; }
  }
}

/* 后端自动暂停事件（截图 resize 持续无法达标）：弹窗错误提示，同步按钮态为「自动采集」 */
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
        '<button class="btn" id="capStopRetry" type="button">重新开启自动采集</button>' +
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
let lostAsking = false; // 后端失联确认框是否正显示（恢复自动收起 / 用户决策后才复位）

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

/* 带超时的 fetch：服务“连得上但请求永不返回”（GC 濒死/线程池耗尽）也按超时算失败，避免失联探测无限挂起 */
function fetchT(url, opt, ms){
  const ctl = new AbortController();
  const timer = setTimeout(()=> ctl.abort(), ms || 3000);
  return fetch(url, Object.assign({}, opt || {}, { signal:ctl.signal })).finally(()=> clearTimeout(timer));
}

/* 探测后端是否仍存活（连接被拒/超时无响应视为已停止） */
function probeAlive(){
  return fetchT("/api/app/meta", { cache:"no-store" }, 3000).then(()=> true).catch(()=> false);
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

/* 后端探测失联（连接失败/请求超时）确认框：自动弹出类似「退出程序」的确认，由用户决定是否结束本程序。
   确定 → 走正常停服流程 startExit（尝试停后端并关页）；取消 → 继续探测等待，服务恢复后自动收起。 */
function showLostAsk(){
  if(lostAsking) return;
  lostAsking = true;
  const ov = document.createElement("div");
  ov.id = "lostAsk";
  ov.innerHTML =
    '<div class="xcard">' +
      '<div class="xt2" style="color:var(--amber)">后端服务状态未知</div>' +
      '<div class="msg">多次探测后端均超时/无响应：服务可能已停止，或内存不足（OutOfMemory）导致进程退出。<br>' +
        '是否结束本程序？结束会尝试停止后端服务并关闭本页。<br>' +
        '若服务只是正在重启，可点「取消」继续等待——恢复后本提示自动消失。</div>' +
      '<div class="xrow">' +
        '<button class="btn" id="lostCancel" type="button">取消（继续等待）</button>' +
        '<button class="btn danger" id="lostQuit" type="button">结束程序</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(ov);
  $("lostCancel").addEventListener("click", ()=>{
    hideLostAsk();
    toast("已取消，继续探测等待后端恢复…", "");
  });
  $("lostQuit").addEventListener("click", ()=>{
    hideLostAsk();
    startExit();
  });
}

function hideLostAsk(){
  const a = document.getElementById("lostAsk"); if(a) a.remove();
  lostAsking = false;
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
      await fetchT("/api/system/shutdown", { method:"POST", keepalive:true, cache:"no-store" }, 2500);
    }catch(e){ /* 服务端可能已先行关闭/无响应，交由下方探测判定 */ }
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

let GROUPS = [];              // 后端组合总览（state×action；首位恒为固定「全部」组）
let selKey = null;            // 当前选中组合 key（= gkey(g)）
let thinkActFil = null;       // 列表动作过滤：null=全部，none/click=只看该动作（固定「全部」组不参与过滤、恒在）
const thinkShown = () => thinkActFil ? GROUPS.filter(g => g.all || g.action === thinkActFil) : GROUPS;
let thinkBusy = false;        // 后台是否正在批量分析
let thinkTaskMsg = "";        // 批量任务进行中主图 dock 的进行态文案（切换分组时仍保持显示）
let lastThinkSig = "";        // 组合列表签名（避免无变化时反复刷新闪烁）
let thinkRun = null;          // 批量分析进行态快照 {keys,stage,processed}：列表 chip「已计算/计算中…」与右侧进度条同节奏刷新（keys=本轮待算组合 key 队列，同后端 runAnalyze 顺序）

// 组合 key：固定「全部」组用不可输入的前缀区分（分类标注名由前端 cleanLabel 与后端双重校验，不会含控制符）
const gkey = g => (g.all ? "\u0001" + "all" + "\u0001" : "") + g.state + "\u0001" + g.action;
const b64u = s => btoa(unescape(encodeURIComponent(s)));   // UTF-8 → Base64（ASCII 安全传目录名）
// Base64 可能含 + / =，直接放查询串会被服务端按 URL 解码成空格等非法字符（导致图片 404/400 裂图），必须再转义一层
const artUrl = (kind, dir, v) => "/api/annotate/think/img/" + encodeURIComponent(kind) + "?dir=" + encodeURIComponent(b64u(dir || ""))
    + (v ? "&v=" + v : "");   // v = 产物 info.json 的 mtime：后台自动重算后 URL 变化 → 浏览器绕过 1h 缓存拉到新对照图
const fmtCov = c => (c == null ? "—" : Number(c) + "%");
// 组列表排序 / 展示用的「像素相同比例」= 交集图 100% 档覆盖率（全部样本一致像素占比，即列表首卡
// 「交集图 100% 覆盖率（完全一致）」角标数值，取 g.sameCov.same100）；旧目录无 same100 键时退回 90% 档（g.coverage）
const covSame = g => {
  const c = g.sameCov && g.sameCov.same100 != null ? g.sameCov.same100 : g.coverage;
  return c == null ? null : Number(c);
};

function thinkSig(){
  return GROUPS.map(g => [g.state,g.action,g.sampleCount,!!g.analyzed,!!g.stale,covSame(g),g.dir,g.mtime||0].join("|")).join("\n");
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
    const tail = g.all
      ? '已合成全部 ' + g.sampleCount + ' 张截图的 12 张产物：交集图六档（100% 覆盖率＝完全一致 + 90/80/70/60/50% 覆盖率＝样本间稳定区）、'
        + '多数图 + 多数图最大差异图、均值图 + 均值图最大差异图、去重均值图 + 去重均值图最大差异图。'
      : g.hasUnique
        ? '对照图已生成：15 张基础合成图（含交集 100/90/80/70/60/50 六档与多数/均值/去重均值/8·32 块图）+ 15 张独有区图' + (g.hasAttn ? ' + 12 张注意区交集图（以关注点为中心）' : '') + (g.hasClick ? ' + 12 张点击区交集图（以点击点为中心）' : '') + '。'
        : '对照图已生成；各独有区图（本分类独有区域）将在随后的后台重算中补齐。';
    const cs = covSame(g);
    text = cs != null
      ? '交集图像素相同比例<b class="kv">' + fmtCov(cs) + '</b>，' + tail
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
/* 右栏顶部统计行（#tkStat）：常驻展示样本库规模与对照图生成进度总览——与「特征验证」的 vkStat 同一层次。
   口径同列表 chip：可分析 = 已标注样本 ≥1 张；已生成 = 产物齐全且样本未变动；需重算 = 样本或产物已变动；
   待生成 = 可分析但尚无对照图（三者相加 = 可分析分组数）。固定第一条「全部」是汇总组、不算分类。 */
function thinkStatLine(){
  const el = $("tkStat"); if(!el) return;
  const cs = GROUPS.filter(g => !g.all);
  if(!cs.length){ el.textContent = "样本库：暂无已标注分类（先在「未标注 / 已标注」里打标）。"; return; }
  const samples = cs.reduce((n, g) => n + (Number(g.sampleCount) || 0), 0);
  const can = cs.filter(g => g.canAnalyze);
  const stale = can.filter(g => g.stale === true).length;
  const done = can.filter(g => g.analyzed && g.stale !== true).length;
  const wait = Math.max(0, can.length - done - stale);      // 待生成（后端未给字段，按总量差额推得）
  let s = "样本库：" + samples + " 张原图 · " + cs.length + " 个分类\n已生成 " + done + "/" + can.length + " 组";
  if(wait || stale) s += "（待生成 " + wait + " · 需重算 " + stale + "）";
  el.textContent = s;
}
/* 右栏进度条（#thinkTask，样式同「特征验证」的 verifyTask）：text=null 隐藏并复位；否则填充 pct 宽度 + 进度行文案 */
function thinkTaskUi(text, pct){
  const box = $("thinkTask");
  if(text == null){
    box.style.display = "none";
    const f = $("thinkFill"); if(f) f.style.width = "0%";
    return;
  }
  box.style.display = "block";
  const f = $("thinkFill"); if(f) f.style.width = Math.max(0, Math.min(100, Math.round(pct || 0))) + "%";
  const el = $("thinkTaskTxt"); if(el){ el.style.color = ""; el.textContent = text; }
}
/* 任务收尾结果行（同「特征验证」完成态）：进度条拉满 + 结果文案上色（成功绿 / 失败红）保留在右栏 */
function thinkTaskFinal(label, t){
  if(!t){ thinkTaskUi(null); return; }
  const ok = t.status !== "error";
  const box = $("thinkTask");
  box.style.display = "block";
  const f = $("thinkFill"); if(f) f.style.width = "100%";
  const el = $("thinkTaskTxt");
  if(el){
    el.style.color = ok ? "var(--green)" : "var(--danger)";
    el.textContent = (label || "分析") + (ok ? "已完成" : "失败") + "：" + (t.message || (ok ? "任务完成" : "任务失败"));
  }
}
/* 任务期间两个按钮一并置灰（开始分析 / 重新生成全部） */
function thinkButtonsBusy(on){
  const s = $("btnThinkStart"), r = $("btnRebuild");
  if(s) s.disabled = !!on;
  if(r) r.disabled = !!on;
}
/* 批量任务收尾：复位 busy 标志与进行态文案 → 重新拉取组合总览，让列表 / 主图 / dock 回到最新状态；
   t/label = pollAnalyze 返回的最终状态，供 thinkTaskFinal 在右栏留下绿色/红色结果行 */
async function thinkTaskDone(t, label){
  thinkBusy = false;
  thinkRun = null;                                      // 任务结束：列表 chip 回到服务端组合状态口径
  thinkTaskMsg = "";
  thinkButtonsBusy(false);                              // 任务结束恢复「开始分析 / 重新生成全部」可用
  if(FILTER !== "think"){ $("thinkBar").hidden = true; syncDockNow(); thinkTaskUi(null); return; }
  await refreshThink(false, false);
  renderThinkList();
  thinkBusyDock(null);   // dock 从“任务进行态”恢复为当前分组的最新状态（覆盖率 / 后台合成中…）
  thinkTaskFinal(label, t);
}
/* 主图区轻占位：无对照图时给一行极简提示；具体原因与下一步见底部浮层状态条 */
function showThinkEmpty(cls, title){
  const te = $("thinkEmpty");
  te.className = ["bad","warn"].includes(cls) ? cls : "";
  $("teTitle").textContent = title || "";
  te.style.display = "flex";
}
const THINK_EMPTY = "没有分类标注";
/* 汇总分析对照图（每张基础图与其 -unique 独有区图成对出现，按族依次排：
  全图交集六档 100/90/80/70/60/50 各带 -unique（共 12 张）→
  多数族 max/major8/major32 各带 -unique（共 6 张）→
  均值族 avg/avg8/avg32 各带 -unique（共 6 张）→
  去重均值族 dedup-avg/dedup-avg8/dedup-avg32 各带 -unique（共 6 张）；
  每个分类另含 12 张注意区交集图 attn8/32-same100/90/80/70/60/50（以关注点为中心，未设 = 屏幕中心），
  鼠标点击分类再加 12 张点击区交集图 click8/32-same100/90/80/70/60/50（以鼠标点击点为中心）
  （均为 1/8、1/32 方框 × 各交集档，均参与识别）。
  识别差异度 = 五族加权平均 (50A+15B+10C+10D+15E)/W：每族先把族内各图「不匹配点占比」等权平均，
  A 全图交集 12 张（权 50）/ B 多数族 6 张（权 15）/ C 均值族 6 张（权 10）/
  D 去重均值族 6 张（权 10）/ E 方框交集区（注意区 + 点击区）24 张（权 15）；
  W = 适用族的权重之和（参与分类产物齐全、五族俱备恒为 100）；
  产物无任何有效像素的空图（独有区图无独有点等）没有可判别的点、无法做区分，判完全不匹配按满值计入、照常参与族均值；
  -unique 图须等全部分组的基础图（15 张：交集六档 + 多数/均值/去重均值/8·32 块族）都生成完后
  由后台统一补算，未生成前本组先不展示独有区图卡片） */
/* 对照图 kind 元数据：唯一权威源 = 后端 act/ArtifactKind，启动时经 /api/app/kinds 取回。
   每项 {kind, file, label, family, crop, block, div, tier, unique}（顺序 = 展示顺序）。
   卡片 DOM、分值短名与卡片 tooltip 全部由这些结构化字段派生 —— 加 / 改 / 删一个 kind 只改后端注册表。 */
let KIND_META = [];        // 后端下发的全部 kind
let ALL_META = [];         // 后端下发的固定「全部」组 12 张专用产物（交集图六档 6 张 + 多数/均值/去重均值 3 族各「代表图 + 差异最大图」）
let kindMetaLoaded = false;
const THINK_CARDS = [];    // [{m, card, img, tcs}]，与 KIND_META 等长：渲染 / 清理直接遍历它
const ALL_CARDS = [];      // [{m, card, img, tcs}]，与 ALL_META 等长：「全部」组专用卡片，与 THINK_CARDS 互斥展示

/* 卡片 tooltip（原 54 条手写文案）：同样按族 / 档位 / 方框形态套模板派生 */
const TIP_OP = "单击打开弹窗；弹窗内单击图片在「自适应缩放 ↔ 原始分辨率」间切换，点空白 / Esc 关闭";
const tipBox = d => "整幅长宽各 1/" + d + "，1280×720 → " + (d === 8 ? "160×90" : "40×22");
function kindTip(m){
  const pct = m.tier ? m.tier.slice("same".length) : "";
  if(m.family === "CROP"){
    const click = m.crop === "CLICK";                 // 关注点未设 = 屏幕中心；点击区仅鼠标点击分类生成，注意区每个分类都有
    const who = click ? "鼠标点击点" : "关注点（未设 = 屏幕中心）";
    const tail = (click ? "仅鼠标点击分类生成" : "每个分类都生成")
      + "；框中心固定不向画幅内收敛，越出画面的部分为透明。" + TIP_OP;
    if(pct === "100"){
      return "以本分类" + who + "为中心的方框交集图（" + tipBox(m.div)
        + "），框内样本完全一致的 100% 档，最严格，参与识别比对；" + tail;
    }
    if(pct === "90"){                                  // 1/8 · 90% 两张是「看要紧位置」的点睛档
      const focus = m.div === 8 ? (click ? "，聚焦要点位置" : "，聚焦要看的位置") : "";
      return "以本分类" + who + "为中心的方框交集图（" + tipBox(m.div) + "）" + focus + "，参与识别比对；" + tail;
    }
    return (click ? "点击区" : "注意区") + "交集图低档：以本分类" + who + "为中心、整幅长宽各 1/" + m.div
      + " 的方框交集图（1280×720 → " + (m.div === 8 ? "160×90" : "40×22") + "），框内样本覆盖>" + pct
      + "% 一致的颜色保留，参与识别比对；" + tail;
  }
  if(m.family === "INTERSECT"){
    if(m.unique){
      return "在交集图 " + pct + "% 覆盖率基础上剔除其它分类同档(" + pct
        + "%)同位同色的像素，剩余为本分类独有区域（参与识别比对）；单击打开弹窗";
    }
    if(pct === "100"){
      return "交集图 100% 档：样本在该像素完全一致（全部截图同色）才保留该真实色，最严格、只留下各截图间恒定的像素，参与识别比对与独有区互比；" + TIP_OP;
    }
    if(pct === "90"){
      return "交集图 90% 档：样本在该像素覆盖>90% 时取主流色的公共画面，参与识别比对与独有区互比；" + TIP_OP;
    }
    return "交集图档：样本覆盖>" + pct + "% 时取主流色，参与识别比对；与其它分类同档互比生成独有区图；单击打开弹窗";
  }
  const name = m.family === "MAJOR" ? "多数" : m.family === "AVG" ? "均值" : "去重均值";
  const blk = m.block === 1 ? name + "图" : " 1/" + m.block + " " + name + "图";
  if(m.unique) return "在" + blk + "基础上删除其它分类" + blk + "同位同色的像素，剩余为本分类独有区域；单击打开弹窗";
  if(m.family !== "DEDUP_AVG") return TIP_OP;          // 多数图 / 均值图：产物本身自解释，只给操作提示
  return m.block === 1
    ? "先对全部样本在该像素位置出现过的颜色去重，再对去重后的颜色逐通道取均值（每种颜色一票，重复截图再多也只按一个颜色计，抽样不均不再把平均拉偏）；" + TIP_OP
    : "先对全部样本在该 " + m.block + "×" + m.block + " 块内出现过的颜色去重，再对去重后的颜色逐通道取均值（每种颜色一票，重复截图再多也只按一个颜色计）；" + TIP_OP;
}

/* 卡片图片事件：load 后按「原图一半」排版；失败按需自动重试；单击卡片打开 80% 视口弹窗 */
function bindThinkImg(c){
  const el = c.img, id = el.id;
  el.addEventListener("load", ()=>{
    if(thinkImgState(id).seq !== thinkSeq) return;    // 已切到其它分组：过期图的 load 不参与排版
    applyCardLayout(el);
  });
  el.addEventListener("error", ()=> onThinkImgError(el));
  el.addEventListener("click", ()=>{
    if(!el.getAttribute("src")) return;
    const tn = c.card.querySelector(".tch .tn");
    openLightbox(el.src, (tn && tn.textContent) || id);
  });
}

/* 按后端元数据建一组卡片 DOM（kind 卡片 54 个手写块已删；固定「全部」组 12 张同理） */
function buildCards(meta, list, idPrefix, tipOf){
  const tp = $("thinkPane");
  const frag = document.createDocumentFragment();
  for(const m of meta){
    const card = document.createElement("div");
    card.className = "tcard";
    const ch = document.createElement("div");
    ch.className = "tch";
    const tn = document.createElement("span");
    tn.className = "tn";
    tn.textContent = m.label;
    const tcs = document.createElement("span");
    tcs.className = "tcs";
    tcs.id = "tcs-" + idPrefix + m.kind;
    tcs.textContent = "—";
    ch.append(tn, tcs);
    const box = document.createElement("div");
    box.className = "tcimg";
    const img = document.createElement("img");
    img.id = "img-" + idPrefix + m.kind;
    img.alt = m.label;
    img.title = tipOf(m);
    box.append(img);
    card.append(ch, box);
    frag.append(card);
    const c = { m, card, img, tcs };
    list.push(c);
    bindThinkImg(c);
  }
  tp.append(frag);
}

/* 建全部卡片：分类 kind（42/54 张）在前、「全部」组 12 张在后；两组同处 #thinkPane，按选中组互斥展示 */
function buildThinkCards(){
  $("thinkPane").innerHTML = "";
  THINK_CARDS.length = 0;
  ALL_CARDS.length = 0;
  buildCards(KIND_META, THINK_CARDS, "", kindTip);
  buildCards(ALL_META, ALL_CARDS, "all-", m => m.label);
  for(const c of ALL_CARDS) c.card.style.display = "none";   // 「全部」组卡片默认隐藏
}

/* 取一次 kind 元数据并建卡；返回是否成功（失败由调用方决定是否重试） */
async function loadKindMeta(){
  if(kindMetaLoaded) return true;
  try{
    const r = await fetch("/api/app/kinds", { cache:"no-store" });
    if(!r.ok) return false;
    const j = await r.json();
    if(!j || !Array.isArray(j.kinds) || !j.kinds.length) return false;
    KIND_META = j.kinds;
    ALL_META = Array.isArray(j.all) ? j.all : [];
    kindMetaLoaded = true;
    buildThinkCards();
    return true;
  }catch(e){
    return false;
  }
}

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

/* 顶栏（.stagebar）视图标题：#fname = 视图名、#fsub = 补充说明（无则留空）、#imgDims = 分辨率。
   「特征验证 / 算法调优」这类没有单张图片的视图进入时必须显式写一遍，否则会残留上一个视图的分类名 + 原始截图口径 */
function setStageTitle(name, sub){
  $("fname").textContent = name || "";
  $("fsub").textContent = sub || "";
  $("imgDims").textContent = "";
}

/* 进入汇总分析工作台 */
function enterThink(){
  FILTER = "think";
  dirty = false;
  stateFilter = null;          // 汇总分析不沿用「全部」视图的分类过滤
  imgActFil = null;            // 也不沿用普通截图列表的动作过滤
  updateCountsOnly();          // 入口即同步顶部计数（异步落盘；分组列表由下面 refreshThink 全量刷新）
  syncRightPanel();            // 右栏切到「汇总分析」（隐藏标注编辑与分类过滤）
  $("imgwrap").style.display = "none";
  $("placeholder").style.display = "none";
  $("imgarea").classList.remove("emptycol");   // 汇总分析不沿用空列表的纵向布局与手动采集入口
  $("capManualBtn").style.display = "none";
  showZoomCtl(false);      // 汇总分析各对照图用各自的 lightbox 放大，隐藏主图缩放控件
  resetZoom();
  hideSmartTip();          // 汇总分析模式下不显示「未标注」智能分析建议条
  syncSugDock();           // 汇总分析：处理状态浮层按 thinkBar 内容自动显示
  $("lstTitle").textContent = "分类标注列表（按像素相同比例）";
  thinkActFil = null;            // 动作过滤复位为「全部」
  $("thinkFil").hidden = false;  // 显示列表动作过滤行
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
  thinkActFil = null;
  $("thinkFil").hidden = true;
  for(const c of THINK_CARDS){
    c.img.removeAttribute("src");
    c.card.style.display = "";
  }
  for(const c of ALL_CARDS){
    c.img.removeAttribute("src");
    c.card.style.display = "none";
  }
}

function renderThinkList(){
  syncFilRows();   // 过滤行：汇总分析视图显示动作过滤（#thinkFil）
  setSegState();   // 筛选按钮仍显示全部/未标注/已标注的截图计数，选中态切到“汇总分析”
  const L = thinkShown();
  const nReal = GROUPS.reduce((n,g)=> n + (g.all ? 0 : 1), 0);   // 「N 个」只数分类标注组合（固定「全部」组不计入）
  const nFil = L.reduce((n,g)=> n + (g.all ? 0 : 1), 0);
  $("listCount").textContent = (thinkActFil ? nFil + " / " : "") + nReal + " 个";   // 任务进度不再挤进列表头小角，改由右下角 taskTip 闪现提示
  const ul = $("imgList"); ul.innerHTML = "";
  for(const g of L){
    const li = document.createElement("li");
    li.className = "row" + (selKey === gkey(g) ? " on" : "");
    li.dataset.key = gkey(g);              // 供批量分析推进时按 key 轻量刷新 chip
    const ck = thinkChipFor(g);
    const cs = covSame(g);
    let extra = g.sampleCount + " 张";
    if(cs != null) extra += " · 相同 " + fmtCov(cs);
    // 固定第一条「全部」：不是某个分类标注、没有动作/点击点，只有 12 张全库专用产物
    if(g.all) li.title = "全部已标注截图的 12 张产物：交集图六档（100% 覆盖率＝公共部分 + 90/80/70/60/50% 覆盖率＝样本间稳定区）+ 多数/均值/去重均值各自的最大差异图";
    li.innerHTML =
      '<div class="r1"><span class="t">' + escHtml(g.state) + '</span>' +
      (g.all ? '' : '<span class="actx">' + (ACT_LABEL[g.action] || g.action) + '</span>') +
      '<span class="chip vkc ' + ck.cls + '" data-t="' + ck.txt + '" data-c="' + ck.cls + '">' + ck.txt + '</span></div>' +
      '<div class="r2">' + extra.replace(/</g,"&lt;") + '</div>' +
      (cs != null
        ? '<div class="tbar"><i class="' + (cs >= 100 ? "full" : "") + '" style="width:'
            + Math.max(0, Math.min(100, cs)) + '%"></i></div>'
        : '');
    li.addEventListener("click", ()=> openGroup(g));
    ul.appendChild(li);
  }
  if(!L.length){
    const d = document.createElement("li"); d.className = "empty";
    d.textContent = nReal
      ? "没有「" + (ACT_LABEL[thinkActFil] || thinkActFil) + "」动作的分类"
      : THINK_EMPTY;
    ul.appendChild(d);
  }
}

/* 组合行状态 chip（与特征验证共用 vkc 配色；进行态快照 thinkRun 与右侧任务进度条同一消息源）：
   vd 已计算 / vs 需重算 / vr 计算中… / vn 待生成（有样本尚未生成）或无样本 */
function thinkChipFor(g){
  if(!g.canAnalyze) return { txt:"无样本", cls:"vn" };
  if(thinkRun){
    const i = thinkRun.keys.indexOf(gkey(g));
    if(i >= 0){
      if(thinkRun.stage >= 2 || i < thinkRun.processed) return { txt:"已计算", cls:"vd" };
      if(i === thinkRun.processed) return { txt:"计算中…", cls:"vr" };
    }
  }
  if(g.analyzed && !g.stale) return { txt:"已计算", cls:"vd" };
  if(g.analyzed) return { txt:"需重算", cls:"vs" };
  return { txt:"待生成", cls:"vn" };
}
/* 批量分析推进（每轮 task 轮询）时轻量刷新各行 chip：只改有变化的行，不整列重建，避免打断查看/点击 */
function syncThinkRowChips(){
  if(FILTER !== "think") return;
  for(const li of $("imgList").querySelectorAll("li.row")){
    const key = li.dataset.key;
    const g = key != null ? (GROUPS.find(x => gkey(x) === key) || null) : null;
    if(!g) continue;
    const c = thinkChipFor(g);
    const el = li.querySelector(".chip.vkc");
    if(!el || (el.dataset.t === c.txt && el.dataset.c === c.cls)) continue;
    el.textContent = c.txt;
    el.className = "chip vkc " + c.cls;
    el.dataset.t = c.txt;
    el.dataset.c = c.cls;
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
  if(FILTER !== "think") return;   // 加载期间已切走：丢弃本次结果，防止把已退出的汇总分析主区重新点亮
  const prevKey = selKey;
  GROUPS = arr;
  thinkStatLine();               // 右栏顶部统计行（样本库规模 + 已生成 / 待生成 / 需重算总览）
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

/* 自动选中一组：在当前（动作过滤后）可见的组合里优先挑已分析的分类；
   固定第一条「全部」虽是列表首行，但不作为默认选中项（仍保留上次/分类默认视图） */
function autoPick(){
  const L = thinkShown().filter(x => !x.all);
  if(!L.length){ openGroup(null); return; }
  openGroup(L.find(x => x.analyzed && !x.stale) || L[0]);
}

/* 动作过滤按钮（汇总分析=thinkActFil / 全部、已标注=imgActFil）：互斥单选，再点已选中的按钮取消（回全部）。
   普通截图视图过滤后当前图被滤掉时自动跳到可见第一张（与分类过滤同款处理）。 */
function toggleThinkFil(a){
  thinkActFil = thinkActFil === a ? null : a;
  renderThinkList();
  if(!thinkActFil) return;
  const g = selKey ? GROUPS.find(x => gkey(x) === selKey) : null;
  if(!(g && g.action === thinkActFil)) autoPick();
}
function toggleImgActFil(a){
  if(FILTER !== "all" && FILTER !== "marked") return;   // 未标注视图不使用动作过滤
  if(a === imgActFil) a = null;
  const old = imgActFil;
  imgActFil = a;
  const L = listNow();
  if(!L.length){
    if(dirty){ imgActFil = old; renderList(); return; }   // 有未保存编辑时不因过滤清屏
    curName = null;
    renderList();
    showEmpty(a ? "没有「" + (ACT_LABEL[a] || a) + "」动作的" + (FILTER === "marked" ? "已标注" : "") + "截图"
      : (FILTER === "marked" ? "" : ""));
    return;
  }
  const keep = itemOf(curName);
  const next = keep && L.some(i => i.name === keep.name) ? keep.name : L[0].name;
  if(dirty && next !== curName && !confirm("当前标注尚未保存，确定切换到其他图片？")){
    imgActFil = old; renderList(); return;
  }
  selectTarget(next);
}
$("thinkFil").addEventListener("click", e => {
  const b = e.target.closest("button");
  if(b && b.dataset.a){
    if(FILTER === "think") toggleThinkFil(b.dataset.a);
    else toggleImgActFil(b.dataset.a);
  }
});
/* 特征验证过滤按钮（#vkFil，data-v）：互斥单选，再点已选中的按钮恢复全部 */
$("vkFil").addEventListener("click", e => {
  const b = e.target.closest("button");
  if(b && b.dataset.v && FILTER === "verify") toggleVkFil(b.dataset.v);
});

/* ---------------- 汇总分析 · 对照图“原图一半”列表排版 ----------------
   全幅对照图（交集/多数/均值及各自 -unique 独有区图）统一按“原图的一半”展示：原尺寸平滑缩到 1/2；
   1/8、1/32 压缩图按整数倍放大到与半尺寸接近（保留像素锐利），保证一行视觉整齐 */
let thinkMeta = null;       // { halfW, halfH } 当前组的列表基准尺寸
let thinkColT = null;       // 窗口 resize 时对照图列宽重排的防抖计时器

/* 对照图网格列宽：卡片宽固定 = 原图 1/2 + 边框（保持长宽比画布），能放下几列就排几列；
   整列放不下的剩余空间留白、图多出高度由滚动条承载；仅当主图区窄到连一张整卡都放不下时，
   把唯一一列收窄到可用宽、图随卡片等比缩小，保证整卡完整可见（绝不横向裁掉右半） */
function setThinkCols(tp, colW){
  const target = Math.max(120, (Number(colW) || 700) - 20);   // 目标图宽 = 原图一半（colW 原为 目标宽+留白）
  const cardW = target + 2;                                   // 卡片宽 = 图宽 + 左右 1px 边框
  const cw = Math.max(1, tp.clientWidth - 24);                // 网格内容可用宽（减去左右 padding 各 12）
  const gap = 12;
  const cols = Math.max(1, Math.floor((cw + gap) / (cardW + gap)));   // 按完整卡片数排，放得下就绝不缩列
  const colWpx = cols > 1 || cw >= cardW ? cardW : cw;        // 一列都放不下时单列收窄到可用宽
  tp.style.gridTemplateColumns = "repeat(" + cols + ", " + colWpx + "px)";
}

function cardTargetScale(nw, nh){
  if(!thinkMeta || !thinkMeta.halfW || !thinkMeta.halfH) return { s:1, pixel:false };
  const halfW = thinkMeta.halfW, halfH = thinkMeta.halfH;
  if(nw >= halfW && nh >= halfH) return { s: halfW / nw, pixel:false };   // 原图半缩放（平滑）
  let k = Math.max(1, Math.floor(halfW / nw));                            // 小图按整数倍放大
  k = Math.max(1, Math.min(k, Math.floor(halfH / nh)));
  return { s: k, pixel: k > 1 };
}

/* 图片 load 后：把所在画布（.tcimg）固定为该图自身长宽比，图再由 CSS contain 等比铺满画布，
   绝不变形；小块降采样图按整数倍放大后保留像素锐利 */
function applyCardLayout(img){
  if(!thinkMeta || FILTER !== "think") return;
  const nw = img.naturalWidth, nh = img.naturalHeight;
  if(!nw || !nh) return;
  const box = img.closest(".tcimg");
  if(box) box.style.aspectRatio = nw + " / " + nh;
  img.style.width = "";
  img.style.height = "";
  img.style.imageRendering = cardTargetScale(nw, nh).pixel ? "pixelated" : "auto";
}

/* 渲染固定「全部」汇总组的 12 张专用卡片（交集图六档 6 张 + 多数/均值/去重均值 3 族各「代表图 + 差异最大图」）；
   角标数值与命中原图取自 g.items（kind → {kind, pct, src?}）；返回实际展示张数 */
function renderAllCards(g, W, H){
  const items = new Map((g.items || []).map(it => [it.kind, it]));
  let n = 0;
  for(const c of ALL_CARDS){
    const m = c.m, it = items.get(m.kind);
    if(!it){ c.tcs.textContent = ""; c.card.style.display = "none"; continue; }
    const url = artUrl(m.kind, g.dir, g.mtime);
    const box = c.img.closest(".tcimg");
    if(box && W && H) box.style.aspectRatio = W + " / " + H;
    c.img.src = url;
    armThinkRetry(c.img, url);
    if(c.img.complete && c.img.naturalWidth) applyCardLayout(c.img);
    c.card.style.display = "";
    const v = it.pct == null ? "—" : Number(it.pct) + "%";
    // 代表图角标：交集图 = 覆盖像素占比，多数/均值/去重均值 = 全部原图与该图的平均差异；差异最大图 = 差异占比
    c.tcs.textContent = m.diff ? "差异 " + v : (m.family === "INTERSECT" ? "覆盖 " + v : "平均差异 " + v);
    c.img.title = m.diff
      ? m.label + "；命中原图：" + (it.src || "—") + "（差异 " + v + "）"
      : m.label + "（" + v + "）";
    n++;
  }
  return n;
}

/* 在右侧/主区展示某组；g=null 清空。切换分组会同步刷新主图区与底部状态 dock，避免残留上一分组 */
function openGroup(g){
  closeLightbox();
  if(!kindMetaLoaded){               // kind 元数据尚未取回（仅首次启动的极短竞态）：取回后自动重放本次选择
    loadKindMeta().then(ok => { if(ok) openGroup(g); });
    return;
  }
  thinkSeq++;                       // 分组切换序号：上一组在途加载 / 失败重试整体作废
  selKey = g ? gkey(g) : null;
  renderThinkList();
  const imgs = THINK_CARDS.concat(ALL_CARDS).map(c => c.img);
  const tp = $("thinkPane");
  for(const el of imgs){
    clearThinkRetry(el);
    el.removeAttribute("src");
    el.style.width = ""; el.style.height = "";
    el.style.imageRendering = "auto";
    const box = el.closest(".tcimg");
    if(box) box.style.aspectRatio = "";   // 画布比例清掉：随新分组图片 load 后按实际比例重建
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
    return;
  }
  const isAll = !!g.all;                 // 固定第一条「全部」：不是分类标注，只有 12 张全库专用产物
  $("fname").textContent = isAll ? g.state : g.state + " ｜ " + (ACT_LABEL[g.action] || g.action);
  // 顶栏口径：原始截图；已生成时再追加分析产出（与右侧文案一致）
  const W = Number(g.width) || 0, H = Number(g.height) || 0;
  $("fsub").textContent = (isAll ? "全部截图 " : "原始截图 ") + g.sampleCount;
  $("imgDims").textContent = "";
  let info = isAll
    ? '【全部截图】<b class="kv">' + g.sampleCount + ' 张</b><br>'
    : '【分类标注】<b class="kv">' + escHtml(g.state) + '</b><br>'
      + '【匹配动作】<b class="kv">' + (ACT_LABEL[g.action] || g.action) + '</b><br>';
  if(W && H){
    info += '【原始分辨率】<b class="kv">' + W + '×' + H + '</b><br>';
  }
  if(!isAll){
    info += '【原始截图】<b class="kv">' + g.sampleCount + ' 张</b>';
  }
  if(g.analyzed){
    tp.style.display = "grid";           // 恢复为 CSS 网格（对照图）
    te.style.display = "none";
    const sz8 = v => Math.max(1, Math.floor(v / 8));
    const sz32 = v => Math.max(1, Math.floor(v / 32));
    if(W && H){
      thinkMeta = { halfW: Math.max(1, Math.round(W / 2)), halfH: Math.max(1, Math.round(H / 2)) };
      setThinkCols(tp, thinkMeta.halfW + 20);
    } else {
      setThinkCols(tp, 700);
    }
    // 独有区图覆盖率（kind → 独有像素占该图全图百分比；未生成时为 —）
    const uc = kind => fmtCov(g.uniqueCov ? g.uniqueCov[kind] : null);
    // 点击区 / 注意区交集图非透明像素占比（kind → 框内该档保留像素占框图总像素百分比，口径同独有区覆盖率；旧目录未重算时为 —）
    const cc = kind => fmtCov(g.clickCov ? g.clickCov[kind] : null);
    const ac = kind => fmtCov(g.attnCov ? g.attnCov[kind] : null);
    // 各交集档基础图覆盖率（kind → 该档保留像素占全图百分比；90% 档兼容旧字段 coverage）
    const tierCov = k => {
      let c = g.sameCov ? g.sameCov[k] : null;
      if(c == null && k === "same90") c = g.coverage;
      return fmtCov(c);
    };
    // 100% 档（全部样本一致）产物是否已生成：重算后的 info.sameCov 才有 same100 键，
    // 重算前的旧目录缺图 → same100 主图整卡隐藏，避免留出空框/裂图
    const has100 = !!(g.sameCov && g.sameCov.same100 != null);
    // 卡片标签（tcs 文本）：方框族看框内占比、交集档看覆盖率、-unique 看独有区覆盖率、全幅族看图尺寸、块族看块尺寸
    const labelOf = m => {
      if(m.crop === "ATTN") return ac(m.kind);
      if(m.crop === "CLICK") return cc(m.kind);
      if(m.unique) return uc(m.kind);
      if(m.family === "INTERSECT") return tierCov(m.kind);
      if(m.block === 1) return W + "×" + H;
      const fam = m.family === "MAJOR" ? "多数" : m.family === "DEDUP_AVG" ? "去重均值" : "均值";
      return (m.block === 8 ? sz8(W) + "×" + sz8(H) : sz32(W) + "×" + sz32(H)) + " · " + fam;
    };
    // 方框族门禁：90% 档两图齐全（注意区看 hasAttn、点击区看 hasClick）才展示；100% 与低档十图另需
    // hasAttnLow / hasClickLow（重算前的旧目录可能没有这些产物 → 整卡隐藏，不留空框/裂图）
    const cropOk = m => {
      const ok = m.tier === "same90" ? (m.crop === "ATTN" ? g.hasAttn : g.hasClick)
                                     : (m.crop === "ATTN" ? g.hasAttnLow : g.hasClickLow);
      return !!ok;
    };
    // 实际展示的对照图张数（基础 + 独有区 + 方框族合计），用于顶栏/右侧「分析产出」口径
    let shownCards = 0;
    if(!isAll) for(const c of ALL_CARDS) c.card.style.display = "none";   // 分类组：隐藏「全部」组专用卡片
    for(const c of THINK_CARDS){
      if(isAll){                                        // 「全部」组走 12 张专用卡片：分类 kind 卡片整体隐藏
        c.tcs.textContent = "";
        c.card.style.display = "none";
        continue;
      }
      const m = c.m, kind = m.kind;
      if(kind === "same100" && !has100){                 // 100% 档产物未生成（旧目录）：整卡隐藏
        c.tcs.textContent = "";
        c.card.style.display = "none";
        continue;
      }
      if(m.unique && !g.hasUnique){
        // -unique 独有区图需跨分类对比：本组尚未经「全部分组基础图齐备后的那次重算」生成 → 先不展示
        // （随后台自动重算补齐，或对该组重新分析）
        c.tcs.textContent = "";
        c.card.style.display = "none";
        continue;
      }
      if(m.family === "CROP" && !cropOk(m)){
        c.tcs.textContent = "";
        c.card.style.display = "none";
        continue;
      }
      const url = artUrl(kind, g.dir, g.mtime);
      const box = c.img.closest(".tcimg");
      // 画布先按原图分辨率定比例：同一分组各张图比例一致，避免未加载时高度跳动、各行互挤
      if(box && W && H) box.style.aspectRatio = W + " / " + H;
      c.img.src = url;
      armThinkRetry(c.img, url);                                 // 记录本次加载目标：瞬时失败可按需自动重试
      if(c.img.complete && c.img.naturalWidth) applyCardLayout(c.img);   // 命中缓存时立即排版
      c.card.style.display = "";
      c.tcs.textContent = labelOf(m);
      shownCards++;
    }
    if(isAll) shownCards = renderAllCards(g, W, H);   // 「全部」组 12 张专用卡片
    // 对照图已展示：顶栏与右侧补上分析产出张数（按实际展示计数）
    $("fsub").textContent = (isAll ? "全部截图 " : "原始截图 ") + g.sampleCount + "，分析产出 " + shownCards;
    info += (isAll ? "" : "<br>") + '【分析产出】<b class="kv">' + shownCards + ' 张</b>';
  } else {
    tp.style.display = "none";           // 无对照图 → 主区用占位提示替代空白网格
    for(const c of THINK_CARDS) c.card.style.display = "none";
    for(const c of ALL_CARDS) c.card.style.display = "none";
    // 有 1 张样本即可分析，无对照图只是后台尚未合成完成：只给一行极简空态词；
    // 原因与下一步统一由底部 dock（renderThinkDock）说明，避免重复文案
    showThinkEmpty(g.canAnalyze ? "warn" : "bad", g.canAnalyze ? "正在合成对照图…" : "暂无对照图");
  }
  $("tkInfo").innerHTML = info;
}

/* 分析所有「可分析但尚未生成对照图 / 样本已变动」的组合；label = 任务名（进入视图自动跑 = 自动分析，右栏「开始分析」= 分析） */
async function startAnalyzeIfNeeded(label){
  const taskName = label || "自动分析";
  if(thinkBusy) return;
  const need = GROUPS.filter(g => g.canAnalyze && (!g.analyzed || g.stale === true));
  if(!need.length){ renderThinkList(); return; }
  thinkRun = { keys: need.map(gkey), stage: 1, processed: 0 };   // 记录本轮待算队列（顺序同后端），供列表 chip 推进
  thinkBusy = true; renderThinkList();
  thinkButtonsBusy(true);   // 任务期间「开始分析 / 重新生成全部」置灰
  thinkBusyDock("正在后台" + taskName + "，为「待生成 / 需重算」的组合合成对照图…");
  toast("发现 " + need.length + " 个分组待生成对照图，开始后台分析…", "");
  try{
    const r = await fetch("/api/annotate/think/analyze", {
      method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ force:false })
    });
    if(!r.ok){ let m="HTTP "+r.status; try{ const j=await r.json(); if(j&&j.error)m=j.error; }catch(_){} throw new Error(m); }
    const j = await r.json();
    const t = await pollAnalyze(j.taskId, taskName);
    await thinkTaskDone(t, taskName);
  }catch(e){
    toast("启动分析失败：" + e.message, "err");
    await thinkTaskDone(null, taskName);
  }
}

/* 长任务进度文案统一模板（所有「正在进行中的后台任务」共用，与右下角那条
   「正在检查重复图片：87/848 张（IMG_xxx.png）（已耗时 2 分 50 秒 · 已比对 42 次 · 复用 3613 次）」同款）：
   「正在<动作>：<已完成>/<总数> <单位>（<当前对象>）（已耗时 N 秒[ · <附加计数>]）」
   计数为 0 时省略计数段、cur 为空时省略对象括号、已耗时与附加计数都为空时省略末段括号 */
function progLine(act, done, total, unit, cur, ageTxt, extra){
  const d = Math.max(0, Number(done) || 0), n = Math.max(0, Number(total) || 0);
  const cnt = n > 0 ? d + "/" + n + " " + unit : (d > 0 ? "已完成 " + d + " " + unit : "");
  const tail = (ageTxt ? "已耗时 " + ageTxt : "") + (extra ? (ageTxt ? " · " : "") + extra : "");
  return "正在" + act + (cnt ? "：" + cnt : "") + (cur ? "（" + cur + "）" : "") + (tail ? "（" + tail + "）" : "");
}

/* 已耗时文案（秒 → 「N 秒 / N 分 M 秒」）：轮询展示中逐秒走动，让排队与长计算可分辨是否仍在推进 */
function durTxt(sec){
  sec = Math.max(0, Math.floor(sec));
  if(sec < 60) return sec + " 秒";
  const m = Math.floor(sec / 60), r = sec % 60;
  return m + " 分" + (r > 0 ? " " + r + " 秒" : "");
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
      // 后台为单线程串行计算池（手动批量分析 / 自动重算共用，执行序 = 提交序）：
      // 任务刚提交可能还在排队（queuePos>0，得等前面的任务跑完），也可能已进场做准备工作。
      // 进度文案统一走 progLine()（与右下角「正在检查重复图片：87/848 张（文件名）（已耗时 2 分 50 秒 · 已比对 42 次 · 复用 3613 次）」
      // 同一模板）：「正在<做什么>：<第几项>/<共几项> <单位>（<当前对象>）（已耗时 N 秒）」，已耗时逐秒走动 →
      // 排队等待 / 长计算都能一眼看出仍在推进，而不是一句看不出在干什么、也不知道进度到哪的「正在准备…」。
      // 任务分 3 个阶段：0 = 准备（一键重建先逐张清场 summary/，再逐分类统计待分析组合，prepAct/prepDone/prepTotal/prepCur 计数）、
      // 1 = 逐分类生成 15 张基础对照图（processed/total 计数）、2 = 生成各分类 15 张 -unique 独有区图（current 带 i/15）。
      // 进行中：进度画在汇总分析右栏 vtBar 同款进度条上（准备 / 基础轮按占比、第 2 轮按图种内 i/15 占比），
      // 中间过程不逐步写历史日志（任务开始 / 结束由 toast 各入库一条）；
      // 退出汇总分析视图后无右栏可挂载，回退为 taskTip 单条闪现兜底（noLog，只显示不写日志）
      const sub = Number(t.submittedAtMs) || 0;
      const ageTxt = sub ? durTxt((Date.now() - sub) / 1000) : "";
      const qp = Number(t.queuePos);
      const queued = qp > 0;                       // 还在排队：计算池正被更早提交的任务占用
      const hasN = t.total > 0;
      const stage = Number(t.stage) || 0;
      const curOf = s => String(s || "").replace(/（\s*\d+\s*\/\s*\d+\s*）\s*$/, "");   // 去掉后端附带的计数后缀
      const mk = /（\s*(\d+)\s*\/\s*(\d+)\s*）/.exec(t.current || "");
      const prepTotal = Number(t.prepTotal) || 0, prepDone = Number(t.prepDone) || 0;
      let line = "", pct = 0;
      if(queued){
        line = "正在排队：第 " + qp + " 位（计算池正忙：" + (t.queueActiveLabel || label) + "）"
          + (ageTxt ? "（已耗时 " + ageTxt + "）" : "");
      }else if(stage < 1){
        // 准备阶段：清场逐张删 / 统计逐分类核对产物，都是真实计数
        line = progLine(t.prepAct || "准备分析", prepDone, prepTotal, t.prepUnit || "项", t.prepCur, ageTxt);
        pct = prepTotal > 0 ? prepDone / prepTotal * 100 : 0;
      }else if(stage === 2){
        line = progLine("刷新独有区图", mk ? mk[1] : 0, mk ? mk[2] : 0, "个图种", curOf(t.current) || "准备中", ageTxt);
        pct = mk ? (+mk[1]) / (+mk[2]) * 100 : 0;
      }else{
        line = progLine("分析对照图", hasN ? Math.min(t.processed + 1, t.total) : 0, hasN ? t.total : 0, "个分类",
          curOf(t.current) || "准备中", ageTxt);
        pct = hasN ? t.processed / t.total * 100 : 0;
      }
      thinkBusyDock(line);
      if(FILTER === "think") thinkTaskUi(line, pct);   // 右栏进度行（同特征验证的 vtText），样本库总览常驻上方的 #tkStat
      else taskTip(line, null, true);                  // 无右栏场景兜底：仅展示，不入历史日志
      // 与右侧任务进度条同节奏刷新组合列表 chip：正在合成的组合标「计算中…」、已算完的标「已计算」
      if(thinkRun){
        thinkRun.stage = t.stage === 2 ? 2 : 1;
        thinkRun.processed = Math.max(0, Number(t.processed) || 0);
        syncThinkRowChips();
      }
      continue;
    }
    if(t.status === "error"){ taskTip(null); toast(t.message || "分析失败", "err"); return t; }
    taskTip(null);
    toast(t.message || "分析完成", "ok");
    return t;
  }
  taskTip(null);
  toast("分析耗时过长，已停止等待（可稍后重新进入本栏）", "err");
  return null;
}

/* 「开始分析」：为「可分析但尚无对照图 / 样本已变动」的分类标注后台分析（进入本视图时也会自动跑同一入口）。
   与「重新生成全部」的区别：这是增量分析——产物齐全且样本未变动的分类直接跳过，不清空、不重算已有产物 */
async function thinkStart(){
  if(thinkBusy){ toast("已有分析任务进行中，请稍候", "warn"); return; }
  const need = GROUPS.filter(g => g.canAnalyze && (!g.analyzed || g.stale === true));
  if(!need.length){ toast("全部分类的对照图都已生成且样本未变动，无需分析。", ""); return; }
  await startAnalyzeIfNeeded("分析");   // 与自动路径同入口：后端按提交序增量生成，进度显示在右栏进度条
}

/* 「重新生成全部对照图」：先清空 summary/ 全部产物，再全量重建。删除在后台计算线程内串行执行，
   不会与自动重算/其它分析互踩；产物由 classify/ 已标注样本派生，删除不影响原始截图与标注 */
async function rebuildThink(){
  if(thinkBusy){ toast("已有分析任务进行中，请稍候", "warn"); return; }
  if(!confirm("将清空 summary/ 下全部对照图产物，并从 classify/ 已标注样本重新生成每个分类适用的对照图（15 张基础合成图：交集 100/90/80/70/60/50 六档与多数/均值/去重均值/8·32 块图，各带 1 张独有区图共 15 张；每个分类另含 12 张注意区交集图（以关注点为中心，未设 = 屏幕中心），鼠标点击分类再加 12 张点击区交集图（以点击点为中心），全部参与识别）。\n原始截图与标注不受影响。\n\n确定继续？")) return;
  thinkRun = { keys: GROUPS.filter(g => g.canAnalyze).map(gkey), stage: 1, processed: 0 };  // 全量重建：所有有样本的组合都在本轮队列
  thinkBusy = true; renderThinkList();
  thinkButtonsBusy(true);   // 任务期间「开始分析 / 重新生成全部」置灰
  thinkBusyDock("正在全量重建全部对照图…（将先清空 summary/ 旧产物）");
  try{
    const r = await fetch("/api/annotate/think/rebuild", { method:"POST" });
    if(!r.ok){ let m="HTTP "+r.status; try{ const j=await r.json(); if(j&&j.error)m=j.error; }catch(_){} throw new Error(m); }
    const j = await r.json();
    toast("已清空产物，开始全量重建…", "");
    const t = await pollAnalyze(j.taskId, "全量重建");
    await thinkTaskDone(t, "全量重建");
  }catch(e){
    toast("启动重建失败：" + e.message, "err");
    await thinkTaskDone(null, "全量重建");
  }
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

/* ---------------- 智能分析：未标注图 × 执行模式同一匹配口径（各分类适用对照图的差异度） ---------------- */
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

/* 触发当前未标注图的智能分析（切图 / 清除标记 / 退出汇总分析等时机调用）。
   先停满 1 秒确认停留才发起，快速翻图不触发；期间切走、收起、已标注即取消 */
function refreshSmartTip(){
  const it = cur();
  if(!smartTipVisible() || !it){ hideSmartTip(); return; }
  if(sugDismiss.has(it.name)){ hideSmartTip(); return; }   // 本图曾被手动收起 → 不再自动弹出
  const file = it.name;
  const seq = ++sugSeq;
  sugStop();
  hideSmartTip();
  sugTimer = setTimeout(()=> startSmartAnalysis(seq, file), 1000);
}

/* 停留确认后发起建议：展示“智能分析中”并请求后端开始逐像素比对 */
function startSmartAnalysis(seq, file){
  if(seq !== sugSeq) return;
  if(!smartTipVisible() || !cur() || cur().name !== file){ hideSmartTip(); return; }
  sugRender('<span class="spin"></span><span>智能分析中：正在按执行模式同一口径，把该截图与各分类适用的对照图（基础图 + 独有区图 + 12 张注意区交集图（以关注点为中心）+ 点击分类 12 张点击区交集图（以点击点为中心））做逐像素差异比对…</span>');
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
    renderSuggest(t.candidates || [], t.rawBest || null);
  };
  tick();
}

/* 渲染智能建议：与执行模式同一口径——差异度 diffPercent = 五族加权 (50A+15B+10C+10D+15E)/W（A 全图交集 12 张权 50、B 多数 6 张权 15、C 均值 6 张权 10、D 去重均值 6 张权 10、E 方框交集区（注意区 12 + 点击区 12）24 张权 15：注意区以关注点为中心每个分类都有、点击区以点击点为中心仅点击分类有；每族先对族内各图等权平均；W = 适用族的权重之和（参与分类产物齐全、恒为 100）；产物无任何有效像素的空图判完全不匹配、按满值计入、照常参与族均值），越小越像；候选另带一行「按已分类原图匹配」（rawBest）：与全部已分类原始截图逐像素完全一致直比的最低一张，与对照图候选合并后统一按差异分值由小到大排序，最小的那行就是顶部建议分类 */
function renderSuggest(list, rawBest){
  const comp = (list && list.length) ? list : [];
  const raw = (rawBest && typeof rawBest.diffPercent === "number") ? rawBest : null;
  // 候选 = 对照图候选（前 3）与「按已分类原图匹配」直比行合并，统一按差异分值由小到大排序，最小那行就是顶部建议与按钮选中的分类
  const diffOf = g => (typeof g.diffPercent === "number") ? g.diffPercent : Number.MAX_VALUE;
  const items = comp.slice(0, 3);
  if(raw) items.push(raw);
  if(!items.length){
    sugRender('<span class="sb-title">智能分析</span>' +
      '<span>还没有可参考的对照图：请先在标注模式把同一画面的截图标成同一分类标注（每类 ≥1 张即可，越多越稳），并到「汇总分析」栏生成对照图（生成该分类适用的全部对照图——基础图 + 独有区图 + 12 张注意区交集图（以关注点为中心）+ 点击分类 12 张点击区交集图（以点击点为中心）——即可参与比对）。</span>');
    return;
  }
  items.sort((a, b) => diffOf(a) - diffOf(b));
  const top = items[0];
  const pct = (typeof top.diffPercent === "number") ? top.diffPercent.toFixed(2) + "%" : "—";
  // 识别已不设阈值门槛（与执行模式一致）：差异度仅作相近程度参考，不再按阈值区分「已识别 / 未识别」
  const actTxt = (top.action && top.action !== "none") ? "（" + escHtml(actLabel(top.action)) + "）" : "";
  const fmtItem = g => (g === raw ? '<span style="color:var(--green)">【按已分类原图匹配】</span> ' : '') +
    '<b>「' + escHtml(g.state) + '」</b> ' +
    (typeof g.diffPercent === "number" ? g.diffPercent.toFixed(2) + "%" : "—");
  const cands = items.map(fmtItem).join('　·　');
  const candBlock = '<span class="cand">候选（差异分值由小到大）：' + cands + '</span>';
  const lowNote =
    '<div style="margin-top:6px">差异度越低表示该画面与该分类的样本越接近；若差异度明显偏高，多半是还没有对照样本的新画面——直接人工标注即可把它归入对应分类的样本池。</div>';
  sugRender(
    '<span class="sb-title">智能分析</span>' +
    '<span class="sug">建议分类标注：<b>「' + escHtml(top.state) + '」</b>' + actTxt +
      ' <span style="color:var(--green)">差异度 ' + pct + '（越低越接近样本）</span></span>' +
    candBlock +
    '<button class="sb-btn" id="sugAdopt" type="button">填入此分类标注</button>' +
    '<span class="expl">与执行模式完全同一套匹配：把该截图与每个分类适用的对照图（15 张基础图：交集六档 100/90/80/70/60/50（100% = 样本像素完全一致）、多数/均值/去重均值/8·32 块图，各带 1 张 -unique 独有区图共 15 张，全部参与比对；每个分类另含 attn8/32-same100/90/80/70/60/50 十二张注意区交集图——以该分类关注点（未设 = 屏幕中心）为心的 1/8、1/32 方框 × 各交集档；鼠标点击分类再加 click8/32-same100/90/80/70/60/50 十二张点击区交集图——以鼠标点击点为心）分别同尺度逐点比对。逐点判据按维度类别分两套：交集/多数/方框交集类（全部交集档、多数/多数块图、注意区与点击区交集图及各自 -unique）颜色来自样本真实像素，要求逐像素完全一致（R/G/B 三通道差都为 0）；均值类（均值/去重均值/均值块图/去重均值块图及各自 -unique）颜色是样本平均色 / 去重平均色，走逐通道容差（三通道差都不超过 execute.rgb-dist-threshold 才匹配，默认 255/3=85，任一通道 > 它判「不匹配」）。分类差异度 = 五族加权平均：(50A+15B+10C+10D+15E)/W，A 全图交集（交集六档及各自 -unique，12 张，权 50）、B 多数（max/major8/major32 及各自 -unique，6 张，权 15）、C 均值（avg/avg8/avg32 及各自 -unique，6 张，权 10）、D 去重均值（dedup-avg/8/32 及各自 -unique，6 张，权 10）、E 方框交集区（注意区 12 + 点击区 12，共 24 张，权 15：注意区以关注点为中心每个分类都有、点击区以点击点为中心仅点击分类有）——每族先把族内各图不匹配点占比等权平均再加权；W = 适用族的权重之和（参与分类产物齐全、恒为 100），越小越像；产物无任何有效像素的空图（独有区图无独有点等）没有可判别的点、无法做区分，判完全不匹配、按不匹配占比满值计入并照常参与族均值、不报错；不按识别阈值区分「已识别 / 未识别」，差异度仅供人工标注参考；独有区图只在“该分类独有的画面区域”上计分，专门拉开相近分类的差距，独有像素为空即空图、该维判完全不匹配、不给该分类留任何靠它“完美命中”的口子；不再使用像素一致率 / 平均色差口径。' + lowNote + '</span>');
  const btn = $("sugAdopt");
  if(btn){
    btn.addEventListener("click", ()=>{
      adoptCategory(top.state);
      $("stateInput").focus();
      toast('已填入分类标注「' + top.state + '」，并自动带入该分类统一的动作与关注点坐标（可在图上点一下微调）', "ok");
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

/* 汇总分析对照图的 load / error / click 事件在建卡时逐个绑定，见 bindThinkImg */
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
$("capManualBtn").addEventListener("click", capManualShot);   // 「未标注」空列表的手动采集入口（display 由 showEmpty/showImage 控制）
$("btnLog").addEventListener("click", openLogPanel);
$("btnExit").addEventListener("click", requestExit);
$("btnRebuild").addEventListener("click", rebuildThink);
$("btnThinkStart").addEventListener("click", thinkStart);
$("btnSaveNext").addEventListener("click", ()=> saveCurrent(true));
$("btnLast").addEventListener("click", cancelCurrentMod);
$("btnClear").addEventListener("click", clearCurrent);
$("btnDelete").addEventListener("click", deleteCurrent);
$("zmOrig").addEventListener("click", ()=> setMainMode("orig"));
$("zmFit").addEventListener("click", ()=> setMainMode("fit"));
$("stateInput").addEventListener("input", ()=>{
  const el = $("stateInput");
  if(BAD_LABEL.test(el.value)) el.value = cleanLabel(el.value);   // 无法作为文件名的符号直接剔除
  setDirty(); updateTagActive();
});
/* 两行单选：动作行选中即把图上编辑目标切到红点（随后点图改点击点）；注意行选中「关注点」即切到绿点 */
document.querySelectorAll('input[name=action]').forEach(r=>{
  r.addEventListener("change", ()=>{
    if(!r.checked) return;
    setAction(r.value, true);
    if(r.value === "click") setPMode("click", false);
  });
});
document.querySelectorAll('input[name=attnUse]').forEach(r=>{
  r.addEventListener("change", ()=>{ if(r.checked) setAttnUse(r.value === "on", true); });
});
/* chip 已选中时再点（radio 不会再触发 change）也要把图上编辑目标切到它对应的点：
   「鼠标点击」→ 红点、「关注点」→ 绿点，避免只想换编辑目标却要先去点另一项 */
document.querySelectorAll(".act[data-a='click']").forEach(el=>{
  el.addEventListener("click", ()=>{ if(actionSel === "click") setPMode("click", false); });
});
document.querySelectorAll(".act[data-n='on']").forEach(el=>{
  el.addEventListener("click", ()=>{ if(attnUse) setPMode("attn", false); });
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
    // 未标注 / 已标注视图：Enter = 保存并跳下一张；「全部」只浏览过滤，Enter = 跳去编辑当前图
    if(FILTER === "unmarked" || FILTER === "marked") saveCurrent(true);
    else if(FILTER === "all") jumpToEdit();
  }
});

window.addEventListener("resize", ()=>{
  if($("imgwrap").style.display !== "none" && naturalW) applyMainZoom();
  renderDot();
  // 汇总分析网格打开期间窗口尺寸变化 → 重算列宽（窄时收窄单列、图等比缩小；宽时回到等宽多列）
  if(FILTER === "think" && $("thinkPane").style.display === "grid"){
    clearTimeout(thinkColT);
    thinkColT = setTimeout(()=>{
      setThinkCols($("thinkPane"), thinkMeta ? thinkMeta.halfW + 20 : 700);
    }, 120);
  }
});

/* ---------------- 特征验证：汇总图算法自回归评分 ---------------- */
let VER = null;              // /api/verify/status 最近一次快照
let vkCnt = null;            // 算法 kind 数（启动即预取一次、进验证视图轮询刷新，供顶栏按钮数字用）
let VK_SEL = null;           // 当前选中的汇总图算法 kind
let VK_DTL = null;           // 当前选中算法的分类级明细
let VK_SEQ = "";             // 列表渲染签名（避免无变化时每 1 秒强制重建 DOM）
let VK_STALE_SEEN = "";      // 已提示过「需重新验证」的指纹（样本 / 产物再变动才会再提示一次）
let VK_TMR = null;           // 特征验证视图下的专用轮询定时器
let vkBusy = false;          // 请求去重（防止上一轮未返回时下一轮叠发）
let vkFil = null;            // 特征验证左栏过滤：null=全部；gen100/c80=只看该条（互斥单选，再点取消）

/* 特征验证过滤判据：k.b = 生成成功率(%)、k.c = 匹配正确率(%)；未验证（null）一律不入选 */
const VK_FIL = {
  gen100: { label: "生成成功率100%", hit: k => k.b != null && k.b >= 100 },
  c80:    { label: "匹配正确率80+%", hit: k => k.c != null && k.c >= 80 }
};
const vkShown = ks => (vkFil && VK_FIL[vkFil]) ? ks.filter(VK_FIL[vkFil].hit) : ks;
function toggleVkFil(v){
  vkFil = vkFil === v ? null : v;   // 再点已选中的按钮 = 恢复全部
  renderVerifyList();
  const ks = vkShown((VER && VER.kinds) || []);
  if(VK_SEL && ks.length && !ks.some(k => k.kind === VK_SEL)) vkSelect(ks[0].kind);   // 选中项被滤出 → 自动切可见第一项
}

/* 汇总图算法短名与维度说明：同样由后端 kind 元数据（族 / 块边长 / 交集档 / 方框形态）派生，
   元数据未就绪时退化为 kind 本身。name = 全站唯一权威短名（特征验证列表 / 右栏标题 / 验证进度 /
   调优视图列表 / 对照图分值弹层全部取它），新增展示位一律用 vkInfo(...).name，勿另写映射 */
function vkInfo(kind){
  const m = KIND_META.find(x => x.kind === kind);
  if(!m) return { name:kind, dim:"" };
  const pct = m.tier ? m.tier.slice("same".length) + "%" : "";
  const uniq = m.unique ? " · 独有区" : "";
  if(m.crop !== "NONE"){
    const t = m.crop === "CLICK" ? "点击区" : "注意区";
    return { name: t + "交集 1/" + m.div + " · " + pct,
             dim: t + "方框（框心 = " + (m.crop === "CLICK" ? "鼠标点击点" : "关注点") + "）" };
  }
  if(m.family === "INTERSECT"){
    return { name: "交集图 " + pct + uniq, dim: "全图交集" + (m.unique ? uniq : " · 覆盖率档") };
  }
  const nm = m.family === "MAJOR" ? "多数图" : m.family === "AVG" ? "均值图" : "去重均值图";
  return { name: nm + " " + "1/" + m.block + uniq,
           dim: nm + uniq };
}
function fmtV(x){
  if(x == null || isNaN(x)) return "—";
  return (Math.round(x * 100) / 100).toFixed(2) + "%";
}
// B 命中比例告警配色：<50 红、<90 黄、其余默认色
function vkBC(b){
  if(b == null || isNaN(b)) return "";
  return b < 50 ? "vkbad" : b < 90 ? "vkwarn" : "";
}
// E 无法区分告警配色：越高越差 → ≥50 红、≥20 黄、其余默认色
function vkE(e){
  if(e == null || isNaN(e)) return "";
  return e >= 50 ? "vkbad" : e >= 20 ? "vkwarn" : "";
}
function vkTime(ms){
  const d = new Date(ms);
  return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0") + ":" + String(d.getSeconds()).padStart(2, "0");
}
function vkCost(ms){
  if(ms == null) return "—";
  return ms < 60000 ? Math.max(1, Math.round(ms / 1000)) + " 秒" : (ms / 60000).toFixed(1) + " 分";
}
function vkSig(j){
  // 进行中任务也进签名：全部 kind 重算时 state 不变（仍 done），「计算中…」chip 需随当前 kind 切换、
  // 任务结束清除，否则列表只按 kind 状态重绘会漏掉这些不改变状态的变化（与右栏进度条脱节）
  const t = j.task;
  const runTag = (t && (j.running || t.finished)) ? (j.running ? "run@" + (t.cur || "") : "fin") : "idle";
  return j.samples + "|" + j.groups + "|" + runTag + "\n" +
    (j.kinds || []).map(k => k.kind + "|" + k.state + "|" + k.a + "|" + k.b + "|" + k.c + "|" + k.e + "|" + k.samples).join("\n");
}

/* 进入特征验证工作台（左栏 = 汇总图算法列表；主图区 = 选中算法的 A/B/C 分值明细；右栏 = 说明与开始验证） */
function enterVerify(){
  FILTER = "verify";
  dirty = false;
  stateFilter = null;
  imgActFil = null;            // 特征验证视图不沿用普通截图列表的动作过滤
  updateCountsOnly();
  syncRightPanel();
  $("imgwrap").style.display = "none";
  $("placeholder").style.display = "none";
  $("imgarea").classList.remove("emptycol");   // 特征验证不沿用空列表的纵向布局与手动采集入口
  $("capManualBtn").style.display = "none";
  showZoomCtl(false);
  resetZoom();
  hideSmartTip();
  syncSugDock();
  $("lstTitle").textContent = "汇总图算法列表（特征验证）";
  $("listCount").textContent = "";
  setStageTitle("特征验证");   // 顶栏只写本视图标题：样本库 / 已算口径由右栏 #vkStat 承担（同理清掉上一个视图残留）
  $("thinkFil").hidden = true;
  vkFil = null;                // 过滤复位：进入即为「全部」，按钮高亮由 renderVerifyList → syncFilRows 刷新
  $("thinkEmpty").style.display = "none";
  $("thinkEmpty").className = "";
  $("thinkPane").style.display = "none";
  selKey = null;
  VK_SEL = null;
  VK_DTL = null;
  VK_SEQ = "";
  const em = $("verifyEmpty"), dt = $("verifyDetail");
  em.style.display = "block"; dt.style.display = "none";
  $("verifyPane").style.display = "flex";
  renderVerifyList();
  if(!VK_TMR) VK_TMR = setInterval(vkPoll, 1000);
  vkPoll();
}

function exitVerify(){
  closeLightbox();
  if(VK_TMR){ clearInterval(VK_TMR); VK_TMR = null; }
  $("verifyPane").style.display = "none";
  $("edVerify").style.display = "none";
  $("thinkEmpty").style.display = "none";
  $("thinkEmpty").className = "";
  $("thinkPane").style.display = "none";
  $("thinkFil").hidden = true;
  $("thinkBar").hidden = true;
  syncDockNow();
  VK_SEL = null;
  VK_DTL = null;
  VER = null;
  VK_SEQ = "";
}

function renderVerifyList(){
  syncFilRows();   // 过滤行：特征验证视图只显示验证结果过滤（#vkFil）
  setSegState();
  const j = VER;
  const ul = $("imgList"); ul.innerHTML = "";
  if(!j){
    $("listCount").textContent = "";
    const d = document.createElement("li"); d.className = "empty";
    d.textContent = "正在读取验证状态…";
    ul.appendChild(d);
    return;
  }
  const ks = j.kinds || [];
  const shown = vkShown(ks);   // 按过滤条件收窄（未验证 / 未生成成功的算法不在任一条件内）
  $("listCount").textContent = ks.length ? (vkFil ? shown.length + " / " + ks.length : String(ks.length)) + " 种" : "";
  const t = j.task;
  for(const k of shown){
    const li = document.createElement("li");
    li.className = "row vrow" + (VK_SEL === k.kind ? " on" : "");
    const info = vkInfo(k.kind);
    let chip = "未验证", chipCls = "vn";
    if(j.running && t && !t.finished && t.cur === k.kind){ chip = "计算中…"; chipCls = "vr"; }
    else if(k.state === "done"){ chip = "已计算"; chipCls = "vd"; }   // 与汇总分析组合行同一口径（thinkChipFor）
    else if(k.state === "stale"){ chip = "需重算"; chipCls = "vs"; }
    // 左栏只给一个数：匹配正确率（区分度）——生成成功率 / 自分类平均 / 其它分类平均 / 样本数都进明细区，列表保持清爽
    const meta = k.c != null
      ? '匹配正确率 <span class="' + vkBC(k.c) + '">' + fmtV(k.c) + '</span>'
        + (k.e != null ? ' · 无法区分 <span class="' + vkE(k.e) + '">' + fmtV(k.e) + '</span>' : '')
      : (k.a != null ? '匹配正确率 ——' : escHtml(info.dim || info.name));
    li.innerHTML =
      '<div class="r1"><span class="t">' + escHtml(info.name) + '</span>' +
      '<span class="chip vkc ' + chipCls + '">' + chip + '</span></div>' +
      '<div class="r2">' + meta + '</div>';
    li.title = "算法 " + k.kind + (k.samples ? " · " + k.samples + " 张样本" : "");
    li.addEventListener("click", ()=> vkSelect(k.kind));
    ul.appendChild(li);
  }
  if(!shown.length && ks.length){
    const d = document.createElement("li"); d.className = "empty";
    d.textContent = "没有符合「" + (VK_FIL[vkFil] ? VK_FIL[vkFil].label : "") + "」的算法";
    ul.appendChild(d);
  }
}

/* 启动即预取算法 kind 数（顶栏「特征验证(N)」不依赖先进验证视图）；仅取计数，不渲染列表 */
async function vkPrefetch(){
  if(appMode !== "mark") return;
  if(vkBusy) return;
  vkBusy = true;
  try{
    const r = await fetch("/api/verify/status", { cache:"no-store" });
    if(r.ok){
      const j = await r.json();
      const n = j && Array.isArray(j.kinds) ? j.kinds.length : null;
      if(n != null && vkCnt !== n){ vkCnt = n; setSegState(); }
    }
  }catch(_){}
  vkBusy = false;
}

async function vkPoll(){
  if(appMode !== "mark") return;
  if(FILTER !== "verify") return;
  if(vkBusy) return;
  vkBusy = true;
  let j = null;
  try{
    const r = await fetch("/api/verify/status", { cache:"no-store" });
    if(!r.ok) throw new Error("HTTP " + r.status);
    j = await r.json();
  }catch(e){ vkBusy = false; return; }
  vkBusy = false;
  if(FILTER !== "verify") return;   // 等待期间已切走：丢弃本次状态，防止把左栏/明细刷回特征验证
  const prev = VER;
  VER = j;
  if(j && j.kinds) vkCnt = j.kinds.length;
  // 数据一有变动（指纹变了）就提示一次「需重新验证」；数据重新一致后再变动会再提示一次
  const staleN = (j && Array.isArray(j.kinds)) ? j.kinds.filter(k => k.state === "stale").length : 0;
  if(staleN > 0 && j.fp && j.fp !== VK_STALE_SEEN){
    VK_STALE_SEEN = j.fp;
    toast("已标注 / 汇总分析的数据有变动，特征验证结果已过期（" + staleN + " 种算法需重算），请重新验证。", "warn");
  }else if(staleN === 0){
    VK_STALE_SEEN = "";
  }
  const sig = vkSig(j);
  if(sig !== VK_SEQ){ VK_SEQ = sig; renderVerifyList(); }
  vkTaskUi(j);
  const wasRunning = prev ? !!prev.running : false;
  const err = j.task ? j.task.error : null;
  if(wasRunning && !j.running){
    if(err){ toast("特征验证中断：" + err, "err"); }
    else{
      const costTxt = fmtCostSuffix(Number(j.task && j.task.costMs));
      toast("特征验证已完成，结果已缓存" + costTxt + "。", "ok");
      if(VK_SEL) vkLoadDetail(VK_SEL);
    }
  }else if(!VK_SEL && !j.running){
    const first = vkShown(j.kinds || []).find(k => k.state === "done");   // 默认选中只在当前过滤可见的算法里挑
    if(first){ vkSelect(first.kind); return; }
  }
  if(VK_SEL && !VK_DTL) vkLoadDetail(VK_SEL);   // 尚未取过明细（如运行中进入）→ 补取
}

async function vkSelect(kind){
  if(VK_SEL === kind) return;
  VK_SEL = kind;
  renderVerifyList();
  await vkLoadDetail(kind);
}

async function vkLoadDetail(kind){
  const em = $("verifyEmpty"), dt = $("verifyDetail");
  if(!kind){ em.style.display = "block"; dt.style.display = "none"; VK_DTL = null; return; }
  let j = null;
  try{
    const r = await fetch("/api/verify/detail?kind=" + encodeURIComponent(kind), { cache:"no-store" });
    if(!r.ok) throw new Error("HTTP " + r.status);
    j = await r.json();
  }catch(e){ return; }
  if(VK_SEL !== kind) return;
  VK_DTL = j || null;
  renderVerifyDetail();
}

function renderVerifyDetail(){
  const em = $("verifyEmpty"), dt = $("verifyDetail");
  const d = VK_DTL;
  if(!d){ em.style.display = "block"; dt.style.display = "none"; return; }
  em.style.display = "none"; dt.style.display = "flex";
  const info = vkInfo(d.kind);
  $("vdTitle").textContent = info.name;
  const chip = $("vdChip");
  chip.textContent = d.fresh ? "结果有效" : "已过期 · 需重算";
  chip.className = "vdChip vkc " + (d.fresh ? "vd" : "vs");
  $("vdStamp").textContent = "完成 " + vkTime(d.doneMs) + " · 耗时 " + vkCost(d.costMs) + " · kind " + d.kind;
  const genTxt = (d.genOk != null && d.genTotal != null)
    ? '（' + d.genOk + ' / ' + d.genTotal + ' 个分类能生成有效图）' : '';
  // D 的分母 = 能给出结果的样本（可匹配样本里排除「最高分被 ≥2 个分类并列」的）；E 的分母 = 全部可匹配样本
  const decided = (d.cSamples != null && d.tie != null) ? d.cSamples - d.tie : null;
  const cTxt = (decided != null) ? '（可匹配样本 ' + d.cSamples + ' 张，其中能给出结果 ' + decided + ' 张）' : '';
  const eTxt = (d.tie != null && d.cSamples != null) ? '（无法区分 ' + d.tie + ' / ' + d.cSamples + ' 张）' : '';
  $("vdSum").innerHTML =
    '<div class="vcard"><div class="vcap">A · 生成成功率</div>' +
    '<div class="vval">' + fmtV(d.b) + '</div>' +
    '<div class="vsub">能生成有效合成图（产物存在且非全透明）的分类占比 ' + genTxt + '，越高越好</div></div>' +
    '<div class="vcard"><div class="vcap">B · 自分类平均匹配值</div>' +
    '<div class="vval">' + fmtV(d.a) + '</div>' +
    '<div class="vsub">分类包含的每张原图与「该分类的该算法生成图」比对的匹配占比均值（= 100 − 不匹配占比），越高越好；无法生成有效图的不参与统计</div></div>' +
    '<div class="vcard"><div class="vcap">C · 其它分类平均匹配值</div>' +
    '<div class="vval">' + fmtV(d.other) + '</div>' +
    '<div class="vsub">其它分类的每张原图与「该分类的该算法生成图」比对的匹配占比均值（= 100 − 不匹配占比），越低越好；无法生成有效图的不参与统计</div></div>' +
    '<div class="vcard"><div class="vcap">D · 匹配正确率</div>' +
    '<div class="vval ' + vkBC(d.c) + '">' + fmtV(d.c) + '</div>' +
    '<div class="vsub">在能生成有效图的分类里，原图与「所有分类的该算法生成图」匹配、最高分<b>唯一最高</b>且恰是自己分类的占比 ' + cTxt + '，越高越好；无法生成有效图的不参与统计，<b>无法给出最高值的结果</b>（最高分被 ≥2 个分类并列，分值一样、分不出该选哪一类）的也不参与统计（这些样本只计入 E）</div></div>' +
    '<div class="vcard"><div class="vcap">E · 无法区分率</div>' +
    '<div class="vval ' + vkE(d.e) + '">' + fmtV(d.e) + '</div>' +
    '<div class="vsub">全部可匹配样本里，与全部分类的该算法生成图比对后<b>无法给出最高值的结果</b>（最高分被 ≥2 个分类并列，分值一样、分不出该选哪一类）的占比 ' + eTxt + '，越低越好；分母 = 全部可匹配样本（命中 + 无法区分 + 误判 = 可匹配样本数），与 D 的分母不同（D 只算能给出结果的）</div></div>' +
    '<div class="vmeta">样本 ' + d.samples + ' 张 · 参与分类 ' + d.groups + ' 个</div>';
  const tb = $("vdRows"); tb.innerHTML = "";
  // 「查看详细」小按钮（D 列看匹配错误、E 列看无法区分）：表格每次重渲染都是新节点，用事件委托只绑一次；
  // 按钮自带 data-m 标明要哪种明细（内容现从 VK_DTL 取，不缓存节点引用）
  tb.onclick = e => {
    const b = e.target.closest(".vkw");
    if(!b) return;
    const row = (VK_DTL && VK_DTL.rows || [])[Number(b.getAttribute("data-i"))];
    if(row) openVkDetail(row, b.getAttribute("data-m") === "tie" ? "tie" : "wrong");
  };
  if(!d.rows || !d.rows.length || d.samples === 0){
    const tr = document.createElement("tr");
    tr.innerHTML = '<td colspan="8" class="vdnone">该算法当前没有可验证的分类或样本（对应汇总图产物不存在，或没有任何原图能与之比对）。</td>';
    tb.appendChild(tr);
    return;
  }
  for(const [ri, r] of d.rows.entries()){
    const tr = document.createElement("tr");
    const act = (ACT_LABEL[r.action] || r.action || "无动作")
      + (r.clickLeft != null && r.clickTop != null ? "（" + r.clickLeft + "," + r.clickTop + "）" : "");
    // 生成不出有效合成图的分类（无产物 / 全透明空图）：属「无判别点」，其样本不进任何指标统计
    // → 数值列显示 —— 而不是 0%
    const ok = r.valid !== false;
    const dash = "——";
    // 是否生成成功：是 / 否 + 原因（原因随本列给出，动作列只留动作本身）
    const gen = ok ? "是" : '<span class="vmiss">否，'
      + (r.missing ? "未生成对应产物" : "生成结果为全透明图") + '</span>';
    // D 列（匹配正确率）与 E 列（无法区分率）各跟一个「查看详细」小按钮，分别只看匹配错误 / 无法区分；
    // 本列没有对应样本就不出现该按钮（另一列仍可单独打开）。不再限定 D < 100%：D 的分母已排除无法区分样本，
    // 可能出现「D = 100% 但仍有无法区分样本」
    const wrongN = Array.isArray(r.wrong) ? r.wrong.length : 0;
    const tiedN = Array.isArray(r.tied) ? r.tied.length : 0;
    const detBtn = (m, n, title) => (ok && n > 0)
      ? '<button type="button" class="vkw" data-i="' + ri + '" data-m="' + m + '" title="' + title + '">查看详细</button>'
      : "";
    // D 的分母（能给出结果 = 命中 + 误判）；E 的分母 = 本分类全部可匹配样本
    const decidedN = ok && r.decided != null ? r.decided : null;
    tr.innerHTML =
      '<td class="st">' + escHtml(r.state) + '</td>' +
      '<td class="ac">' + escHtml(act) + '</td>' +
      '<td class="nu">' + (ok ? fmtV(r.a) : dash) + '</td>' +
      '<td class="nu nugen">' + gen + '</td>' +
      // D：匹配正确率（分母 = 命中 + 误判，不含无法区分）→「查看详细」只列匹配错误
      '<td class="nu ' + (ok ? vkBC(r.c) : "") + '">' + (ok ? fmtV(r.c) : dash)
        + detBtn("wrong", wrongN, "逐图列出本分类的 " + wrongN + " 张匹配错误：最高分唯一（不是并列）但不是自家分类，"
            + "按被误判到的分类分组，每行「命中 x% · 自家 y%」＝ 与误判分类生成图／与自家生成图的匹配占比；"
            + "这些样本只进 D 的分母、不进 D 的分子")
        + '</td>' +
      // 匹配正确分类的个数 = 命中 / 能给出结果（D 的分母）
      '<td class="nu">' + (ok && r.hit != null ? r.hit + " / " + (decidedN != null ? decidedN : "—") : dash) + '</td>' +
      // E：无法区分率（最高分被 ≥2 个分类并列 → 分不出该选哪一类），越低越好；分母 = 全部可匹配样本
      // →「查看详细」只列无法区分（既不算命中也不算判错：进 E 的分母、不进 D 的分母）
      '<td class="nu ' + (ok ? vkE(r.e) : "") + '">' + (ok && r.e != null ? fmtV(r.e) : dash)
        + detBtn("tie", tiedN, "逐图列出本分类的 " + tiedN + " 张无法区分：最高分被 ≥2 个分类并列（分值一样、"
            + "分不出该选哪一类），每行「并列 A、B · 最高 x% · 自家 y%」＝ 并列到的分类及两边的匹配占比；"
            + "这些样本只进 E 的分母、不进 D 的分母")
        + '</td>' +
      // 无法区分的个数 = 无法区分 / 全部可匹配样本（E 的分母）
      '<td class="nu">' + (ok && r.tie != null ? r.tie + " / " + r.samples : dash) + '</td>';
    tr.title = "样本 " + r.samples + " 张：自分类平均 " + fmtV(r.a) + "，匹配正确率 " + fmtV(r.c)
      + "（命中 " + (r.hit || 0) + " / 能给出结果 " + (decidedN != null ? decidedN : "—") + " 张）"
      + "，无法区分率 " + fmtV(r.e) + "（无法区分 " + (r.tie || 0) + " / " + r.samples + " 张）"
      + (ok ? "" : "；该分类未生成有效产物，其上指标与个数不参与统计（显示 ——）");
    tb.appendChild(tr);
  }
}

/* 分类行「查看详细」弹窗：D 列（匹配正确率）只看匹配错误、E 列（无法区分率）只看无法区分。
   两种弹窗共用同一套骨架（标题 + 一行上下文 chip + 口径说明 + 缩略图列表 + 知道了），只是标题、说明段
   与其数据集不同：① mode = "wrong" —— 与全部分类的该算法生成图比对后最高分「唯一但不是自家」的原图，
   按被误判到的分类分组，便于看出本分类被哪个分类抢走；② mode = "tie" —— 最高分被 ≥2 个分类并列的原图，
   列出并列到的分类（这些样本既不算命中也不算判错，只进 E 的分母、不进 D 的分母）。
   缩略图单击可看原图大图。 */
function openVkDetail(r, mode){
  const wrongAll = (r && Array.isArray(r.wrong)) ? r.wrong : [];
  const tiedAll = (r && Array.isArray(r.tied)) ? r.tied : [];
  const isTie = mode === "tie";
  const wrong = isTie ? [] : wrongAll;
  const tied = isTie ? tiedAll : [];
  if(!wrong.length && !tied.length) return;
  const kind = (VK_DTL && VK_DTL.kind) || "";
  const info = kind ? vkInfo(kind) : { name:"—" };
  const act = ACT_LABEL[r.action] || r.action || "无动作";
  if($("vkDetailModal")) $("vkDetailModal").remove();
  // 匹配错误段：按「被误判到的分类」分组，并把条数多的组排前面（一眼看出本分类主要被谁抢走）
  const groups = [];
  for(const w of wrong){
    let g = groups.find(x => x.state === w.hitState);
    if(!g){ g = { state:w.hitState, action:w.hitAction, items:[] }; groups.push(g); }
    g.items.push(w);
  }
  groups.sort((a, b) => (b.items.length - a.items.length)
    || (a.state < b.state ? -1 : a.state > b.state ? 1 : 0));
  const wrongBody = groups.map(g => {
    const gact = ACT_LABEL[g.action] || g.action || "无动作";
    const head = '<div class="ghead">误判为 <b>' + escHtml(g.state) + '</b>'
      + '<span>' + escHtml(gact) + ' · ' + g.items.length + ' 张</span></div>';
    const items = g.items.map(w =>
      '<div class="row">' +
        '<img loading="lazy" src="' + escHtml(imgUrl(w.file)) + '" alt="' + escHtml(w.file) + '">' +
        '<span class="f">' + escHtml(w.file) + '</span>' +
        '<span class="s" title="该原图与「误判分类的生成图」的匹配占比 / 与「自家生成图」的匹配占比">'
          + '命中 ' + fmtV(w.hitScore) + ' · 自家 ' + fmtV(w.selfScore) + '</span>' +
      '</div>').join("");
    return head + items;
  }).join("");
  const tieBody = '<div class="ghead">无法区分 <b>' + tied.length + ' 张</b>'
      + '<span>最高分被 ≥2 个分类并列（分值一样、分不出该选哪一类）</span></div>'
    + tied.map(w =>
      '<div class="row">' +
        '<img loading="lazy" src="' + escHtml(imgUrl(w.file)) + '" alt="' + escHtml(w.file) + '">' +
        '<span class="f">' + escHtml(w.file) + '</span>' +
        '<span class="s" title="最高匹配占比与被并列到的分类 / 与自家生成图的匹配占比">'
          + '并列 ' + escHtml(w.tiedWith || "—") + ' · 最高 ' + fmtV(w.hitScore)
          + ' · 自家 ' + fmtV(w.selfScore) + '</span>' +
      '</div>').join("");
  const sub = isTie
    ? '本分类 ' + r.samples + ' 张原图里 ' + tied.length + ' 张最高分被 ≥2 个分类「并列」（分值一样、'
      + '分不出该选哪一类）。每行「并列 A、B · 最高 x% · 自家 y%」＝ 并列到的分类及两边的匹配占比（越高越像）；'
      + '这些样本既不算命中也不算判错：只进 E 的分母（无法区分率 = 无法区分 / 可匹配样本）、不进 D 的分母。'
      + '单击缩略图看原图大图。'
    : '本分类 ' + r.samples + ' 张原图里 ' + wrong.length + ' 张最高分「唯一但不是自家分类」（匹配错误）。'
      + '按被误判到的分类分组，每行「命中 x% · 自家 y%」＝ 该原图与「误判分类的生成图」／与「自家生成图」的'
      + '匹配占比（命中越高、自家越低说明越像对方）；这些样本只进 D 的分母（能给出结果 = 命中 + 匹配错误）、'
      + '不进 D 的分子。单击缩略图看原图大图。';
  const ov = document.createElement("div");
  ov.id = "vkDetailModal";
  ov.className = "modal-ov";
  ov.innerHTML =
    '<div class="xcard">' +
      // 标题带上「这是哪个分类（标注 ｜ 动作）的哪种明细」；本弹窗覆盖整页、看不到身后的右栏与表格，
      // 故紧随其后再用一行信息条把「哪种汇总图算法 / 几个样本 / D·E·命中」一并摆出来，不用关掉弹窗去回看
      '<div class="xt2">' + (isTie ? "无法区分明细" : "匹配错误明细") + ' - ' + escHtml(r.state) + ' ｜ ' + escHtml(act) + '</div>' +
      '<div class="xmeta">' +
        '<span>汇总图算法<b>' + escHtml(info.name) + '</b></span>' +
        '<span>样本<b>' + r.samples + ' 张</b></span>' +
        '<span>匹配正确率<b class="' + vkBC(r.c) + '">' + fmtV(r.c) + '</b></span>' +
        '<span>命中<b>' + (r.hit != null ? r.hit + " / " + (r.decided != null ? r.decided : "—") : "—") + '</b></span>' +
        '<span>匹配错误<b>' + wrongAll.length + ' 张</b></span>' +
        '<span>无法区分率<b class="' + vkE(r.e) + '">' + fmtV(r.e) + '</b></span>' +
        '<span>无法区分<b>' + (r.tie != null ? r.tie + " / " + r.samples : "—") + '</b></span>' +
      '</div>' +
      '<div class="sub">' + sub + '</div>' +
      '<div class="list">' + (isTie ? tieBody : wrongBody) + '</div>' +
      '<div style="text-align:center;margin-top:12px"><button type="button" class="btn" id="vkwOk">知道了</button></div>' +
    '</div>';
  document.body.appendChild(ov);
  const close = () => ov.remove();
  ov.addEventListener("click", e => { if(e.target === ov) close(); });
  const ok = $("vkwOk"); if(ok) ok.addEventListener("click", close);
  ov.querySelectorAll("img").forEach(im => im.addEventListener("click", () => openLightbox(im.src, im.alt)));
}

function vkTaskUi(j){
  const t = j.task;
  const ks = j.kinds || [];
  const doneCount = ks.filter(k => k.state === "done").length;
  const staleCount = ks.filter(k => k.state === "stale").length;
  const total = ks.length;
  const bar = $("verifyTask"), fill = $("vtFill"), txt = $("vtText");
  const btn = $("btnVerifyStart"), stat = $("vkStat"), ver = $("vkVerdict");
  let base = "样本库：" + j.samples + " 张原图 · " + j.groups + " 个分类\n已算 " + (doneCount + staleCount) + "/" + total + " 种";
  if(doneCount || staleCount) base += "（已计算 " + doneCount + " · 需重算 " + staleCount + "）";   // 与列表 chip 同口径
  // 结果来自 summary/verify.json（完整缓存）：已标注与汇总分析的数据没变就直接用上次的，不必重算
  if(j.cached && (doneCount || staleCount)) base += "\n已从 summary/" + (j.cacheFile || "verify.json") + " 恢复上次结果（数据没变即可直接用）。";
  if(staleCount) base += "\n注意：已标注 / 汇总分析的数据有变动，需重新验证（" + staleCount + " 种）。";
  if(j.running && t && !t.finished){
    bar.style.display = "block";
    const curName = t.cur ? vkInfo(t.cur).name : "准备中";
    const pct = t.total ? Math.min(100, Math.round(t.done / t.total * 100)) : 0;
    fill.style.width = pct + "%";
    txt.style.color = "";
    txt.textContent = "正在验证「" + curName + "」（" + Math.min(t.done + 1, t.total) + "/" + t.total + "）"
      + (t.totalSamples ? " · 样本 " + Math.min(t.processed + 1, t.totalSamples) + "/" + t.totalSamples : "");
    btn.disabled = true;
    ver.style.display = "none";
  }else{
    btn.disabled = false;
    ver.style.display = "none";
    if(t && t.finished){
      fill.style.width = "100%";
      bar.style.display = "block";
      if(t.error){
        txt.style.color = "var(--danger)";
        txt.textContent = t.error;
        ver.style.display = "none";
      }else{
        txt.style.color = "var(--green)";
        txt.textContent = "最近一次验证已完成：" + t.done + "/" + t.total + " 种算法。";
      }
    }else{
      bar.style.display = "none";
    }
  }
  stat.textContent = base;
}

async function vkStart(){
  let ok = false, started = false;
  try{
    const r = await fetch("/api/verify/start", { method:"POST", cache:"no-store" });
    const j = await r.json();
    started = !!(j && j.started);
    ok = true;
  }catch(e){ ok = false; }
  if(!ok){ toast("无法启动验证：" + "请求失败", "err"); return; }
  if(started){ toast("开始验证全部汇总图算法…", "ok"); }
  else{ toast("已有验证任务在跑，请稍候。", ""); }
  vkPoll();
}

$("btnVerifyStart").addEventListener("click", vkStart);

/* ---------------- 算法调优：特征组合成匹配算法 + 验证分类准确率（/api/optimize/status|start） ---------------- */
let OPT = null;        // /api/optimize/status 最近一次快照
let OPT_TMR = null;    // 本视图专用轮询定时器（1 秒，仅停留该视图时存在）
let optBusy = false;   // 请求去重（上一轮未返回时不叠发）
let optSig = "";       // 主图区算法结构签名（算法 + 特征 + 基础分 X），变化才重建（避免轮询打断 Y 输入）
let optResSig = "";    // 结果区签名（值变化才重建）
let optY = {};         // 权重草稿 { 算法id: { sig, single, en:[bool], vals:[num] } }，起点 = 后端保存的权重 / 默认全启用 + 1
let optWSig = "";      // 后端保存的权重签名（「自动调整参数」落盘 / 手改权重文件后，把新值同步回权重框）
let OPT_STALE_SEEN = ""; // 已提示过「需重新计算」的指纹（三处数据再变动才会再提示一次）
let optCnt = null;     // 算法个数（顶栏「算法调优(N)」用：启动即预取一次、进本视图轮询刷新，与 vkCnt 同一套做法）
let optCntBusy = false;// 预取去重（上一轮未返回时不叠发）

/* 算法特征顺序签名：特征集合变了才把该算法的权重草稿重置为默认 */
function optFeatSig(a){ return ((a && a.features) || []).map(f => f.kind).join(","); }
function optAlgo(id){ return ((OPT && OPT.algos) || []).find(a => a.id === id) || null; }
function optXof(id, i){ const a = optAlgo(id); return a && a.features[i] ? a.features[i].x : 0; }
/* 取（或初始化）某算法的权重草稿：特征集合未变则沿用用户已改的 Y / 启用状态。
   起点 = 后端保存的权重（「自动调整参数」落盘的那份，见 /status 的 algos[].weights），没记录才默认「全启用 + Y = 1」 */
function optYof(a){
  const sig = optFeatSig(a);
  let d = optY[a.id];
  if(!d || d.sig !== sig || d.en.length !== a.features.length){
    // 单一特征算法：权重固定 1、恒启用（只有一个特征时 Y 在加权平均里被约掉，改它没有意义）
    const single = a.features.length <= 1;
    const arr = Array.isArray(a.weights) ? a.weights : [];
    const en = [], vals = [];
    for(let i = 0; i < a.features.length; i++){
      const y = Number(arr[i]);
      const ok = arr[i] != null && !isNaN(y) && y > 0;
      en.push(single ? true : (arr[i] == null || isNaN(y) ? true : y > 0));
      vals.push(single || !ok ? 1 : Math.round(y * 1000) / 1000);
    }
    d = { sig:sig, single:single, en:en, vals:vals };
    optY[a.id] = d;
  }
  return d;
}
function fmtX(v){ if(v == null || isNaN(v)) return "—"; return (Math.round(v * 100) / 100).toFixed(2); }
/* 权重 Y：步进 0.001，故按三位小数显示（0.37 → 0.37、0.333 → 0.333、整数 1 → 1） */
function fmtY(v){ if(v == null || isNaN(v)) return "1"; return String(Math.round(v * 1000) / 1000); }

/* 进入算法调优工作台（左栏 = 算法列表；主图区 = 算法特征与权重 Y + 结果；右栏 = 进度与结论） */
function enterOpt(){
  FILTER = "opt";
  dirty = false;
  stateFilter = null;
  imgActFil = null;            // 算法调优视图不沿用普通截图列表的动作过滤
  updateCountsOnly();
  syncRightPanel();
  $("imgwrap").style.display = "none";
  $("placeholder").style.display = "none";
  $("imgarea").classList.remove("emptycol");
  $("capManualBtn").style.display = "none";
  showZoomCtl(false);
  resetZoom();
  hideSmartTip();
  syncSugDock();
  $("lstTitle").textContent = "算法列表（点击可滚动定位）";
  $("listCount").textContent = "";
  setStageTitle("算法调优");   // 顶栏只写本视图标题：样本 / 分类 / 算法口径由主图区表头（#optMeta）承担
  $("thinkFil").hidden = true;
  $("thinkEmpty").style.display = "none";
  $("thinkEmpty").className = "";
  $("thinkPane").style.display = "none";
  $("verifyPane").style.display = "none";
  $("thinkBar").hidden = true;
  optSig = "";
  optResSig = "";
  optWSig = "";
  optY = {};
  $("optPane").style.display = "block";
  renderOptList();
  optPoll();
  if(!OPT_TMR) OPT_TMR = setInterval(optPoll, 1000);
}

function exitOpt(){
  closeLightbox();
  if(OPT_TMR){ clearInterval(OPT_TMR); OPT_TMR = null; }
  $("optPane").style.display = "none";
  $("edOpt").style.display = "none";
  $("thinkBar").hidden = true;
  syncDockNow();
  OPT = null;
  optSig = "";
  optResSig = "";
  optWSig = "";
  optY = {};
}

/* 左栏：算法列表（点一行滚到主图区对应卡片；chip = 最近一次验证的匹配正确率） */
function renderOptList(){
  syncFilRows();   // 过滤行：算法调优视图两个过滤行都隐藏
  setSegState();
  const ul = $("imgList"); ul.innerHTML = "";
  const j = OPT;
  if(!j){
    $("listCount").textContent = "";
    const d = document.createElement("li"); d.className = "empty";
    d.textContent = "正在读取状态…";
    ul.appendChild(d);
    return;
  }
  const algos = Array.isArray(j.algos) ? j.algos : [];
  const byId = {};
  if(j.result && Array.isArray(j.result.algos)) for(const r of j.result.algos) byId[r.id] = r;
  if(!algos.length){
    $("listCount").textContent = "";
    const d = document.createElement("li"); d.className = "empty";
    d.textContent = (j.verify && j.verify.running) ? "特征验证运行中…" : "请先在「特征验证」视图完成验证";
    ul.appendChild(d);
    return;
  }
  $("listCount").textContent = algos.length + " 个算法" + (j.ready ? "" : "（未计算）");
  for(const a of algos){
    const r = j.ready ? byId[a.id] : null;      // 特征验证未就绪时不沿用旧结果
    const acc = r && r.accuracy != null ? r.accuracy : null;
    // chip 配色与特征验证 / 汇总分析同一口径：未计算 / 无可判定样本 = 灰 vn（未处理态），有数值才按 vkBC 上色
    const chipCls = (r && acc != null) ? vkBC(acc) : "vn";
    const li = document.createElement("li");
    li.className = "row";
    li.innerHTML =
      '<div class="r1"><span class="t">' + escHtml(a.name) + '</span>' +
      '<span class="chip vkc ' + chipCls + '">' + (r ? (acc == null ? "—" : fmtV(acc)) : "未计算") + '</span></div>' +
      '<div class="r2">' + (r ? a.features.length + ' 个特征' +
        (a.note ? ' · <span style="color:var(--amber)">' + escHtml(a.note) + '</span>'
                : (acc != null || r.tie ? ' · 命中 ' + r.hit + '/' + optDecided(r)
                    + (r.tie ? ' · 无法区分 ' + r.tie + '（' + fmtV(r.tieRate) + '）' : '') : ''))
        : '未计算 · ' + ((j.verify && j.verify.running) ? '特征验证运行中…' : '先完成「特征验证」，算法与特征会自动组合')) + '</div>';
    li.addEventListener("click", ()=>{ const el = $("optA" + a.id); if(el) el.scrollIntoView({ behavior:"smooth", block:"center" }); });
    ul.appendChild(li);
  }
}

/* 主图区骨架（只在算法结构 / 基础分变化时重建；轮询仅刷新数值与结果，不打断 Y 输入） */
function optEnsureMain(){
  const p = $("optPane");
  if(!p) return;
  const j = OPT || {};
  const algos = Array.isArray(j.algos) ? j.algos : [];
  const ready = !!j.ready && algos.length > 0;      // 算法已由最新特征验证结果组合
  const sig = algos.length
    ? (ready ? "R|" : "P|") + algos.map(a => a.id + ":" + optFeatSig(a) + ":" + a.features.map(f => f.x).join("/") + ":" + (a.note || "")).join(";")
    : "none";
  if(sig === optSig) return;
  optSig = sig;
  // 特征验证未就绪时后端同样下发全部算法骨架（特征为空、结果显示「未计算」），这里补一张提示卡
  const tip = (algos.length && !ready)
    ? '<div class="optcard"><h4>需要先完成「特征验证」</h4>' +
        '<div class="ocap">算法由特征验证结果自动组合（用生成成功率 / 匹配正确率挑特征，再算基础分 X）。' +
        '下面是 ' + algos.length + ' 个算法的空位：先到「特征验证」视图跑一次，特征与基础分 X 会自动填上，再回来点右上角「验证所有算法」。</div>' +
        '<div class="optWBtnRow"><button type="button" class="btn green" id="optToVerify">去特征验证</button></div>' +
      '</div>'
    : "";
  let cards = "";
  for(const a of algos){
    const d = optYof(a);
    let rows = "";
    if(!a.features.length){
      rows = '<tr><td colspan="5" style="color:var(--muted)">未计算：先完成「特征验证」，这里会自动列出参与的特征与基础分 X</td></tr>';
    }
    // 单一特征算法：权重固定 1（不可编辑、不参与「自动调整参数」），启用勾也一并锁上（取消它 = 这个算法没有特征了）
    const single = a.features.length <= 1;
    for(let i = 0; i < a.features.length; i++){
      const f = a.features[i];
      const eff = d.en[i] ? f.x * d.vals[i] : 0;
      rows +=
        '<tr>' +
          '<td><input type="checkbox" class="optYen" data-a="' + a.id + '" data-i="' + i + '"' + (d.en[i] ? " checked" : "") +
            (single ? " disabled" : "") +
            ' title="' + (single ? "单一特征算法：权重固定 1、不可取消（只有一个特征时权重在加权平均里被约掉）"
                               : "取消勾选 = 该特征不参与本算法（等价于权重 0）") + '"></td>' +
          '<td><span class="t">' + escHtml(vkInfo(f.kind).name) + '</span>' +
            '<div class="ocap" style="margin:0">' + escHtml(f.why || "") +
            '　A ' + fmtV(f.b) + ' · B ' + fmtV(f.a) + ' · C ' + fmtV(f.other) + ' · D ' + fmtV(f.c) +
            ' · E ' + fmtV(f.e) + '</div></td>' +
          '<td class="nu">' + fmtX(f.x) + '</td>' +
          '<td><input type="number" class="optYin" data-a="' + a.id + '" data-i="' + i + '" min="0" max="1" step="0.001" value="' + d.vals[i] +
            (single ? " disabled" : "") +
            ' title="' + (single ? "单一特征算法：权重固定 1、不可编辑（只有一个特征时 Y 在加权平均里被约掉），也不需要自动调整"
                                : "该特征在本算法里的权重 Y（0~1，默认 1，步进 0.001，上下箭头 / 手输都按 0.001 收齐）；点右上角「验证所有算法」生效") + '"></td>' +
          '<td class="nu" id="optEff-' + a.id + '-' + i + '">' + fmtX(eff) + '</td>' +
        '</tr>';
    }
    cards +=
      '<div class="optcard" id="optA' + a.id + '">' +
        '<h4>' + escHtml(a.name) + '</h4>' +
        '<div class="ocap">' + escHtml(a.desc) +
          (a.note ? '<br><b style="color:var(--amber)">' + escHtml(a.note) + '</b>' : '') +
          (single ? '<br><b style="color:var(--muted)">单一特征算法：权重固定 1、不可编辑，也不参与「自动调整参数」。</b>' : '') + '</div>' +
        '<div class="optTblWrap" style="max-height:none"><table class="optTbl"><thead><tr>' +
          '<th>启用</th><th>特征（A 生成成功率 / B 自分类 / C 其它 / D 正确率）</th><th>基础分 X = B − C</th><th>权重 Y</th><th>生效 X × Y</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div>' +
        '<div class="optResWrap" id="optRes-' + a.id + '" style="margin-top:10px"></div>' +
      '</div>';
  }
  p.innerHTML = '<div class="optWrap">' +
    '<div class="optTop"><span class="ot">特征组合算法</span><span class="otSub" id="optMeta">—</span></div>' + tip + cards +
  '</div>';
  const vb = $("optToVerify");
  if(vb) vb.addEventListener("click", ()=> applyFilter("verify"));
  p.querySelectorAll(".optYin").forEach(inp => {
    inp.addEventListener("input", ()=>{
      const d = optY[inp.dataset.a];
      if(!d) return;
      const i = Number(inp.dataset.i);
      const v = Number(inp.value);
      // 夹到 [0,1] 并对齐输入框步进（step=0.001）取三位小数：手输 0.3333 也统一成 0.333
      d.vals[i] = isNaN(v) ? 1 : Math.round(Math.max(0, Math.min(1, v)) * 1000) / 1000;
      const cell = $("optEff-" + inp.dataset.a + "-" + i);
      if(cell) cell.textContent = fmtX(d.en[i] ? d.vals[i] * optXof(inp.dataset.a, i) : 0);
      optSyncRunBtn();
    });
    inp.addEventListener("change", ()=>{      // 失焦 / 回车后把截断后的值写回输入框
      const d = optY[inp.dataset.a];
      if(d) inp.value = d.vals[Number(inp.dataset.i)];
    });
  });
  p.querySelectorAll(".optYen").forEach(cb => {
    cb.addEventListener("change", ()=>{
      const d = optY[cb.dataset.a];
      if(!d) return;
      const i = Number(cb.dataset.i);
      d.en[i] = cb.checked;
      const cell = $("optEff-" + cb.dataset.a + "-" + i);
      if(cell) cell.textContent = fmtX(d.en[i] ? d.vals[i] * optXof(cb.dataset.a, i) : 0);
      optSyncRunBtn();
    });
  });
}

/* 正确率分母 = 能给出结果的样本（命中 + 判错）= 可判定样本 − 无法区分，与特征验证的 D 同口径 */
function optDecided(r){
  if(r && r.decided != null) return r.decided;
  return ((r && Number(r.samples)) || 0) - ((r && Number(r.tie)) || 0);
}

/* 单个算法的结果卡（大数字 = 匹配正确率 + 无法区分率 + 各分类明细，details 折叠） */
function optResCard(r){
  const rows = Array.isArray(r.rows) ? r.rows : [];
  const miss = x => (x.miss == null ? (x.samples - x.hit - (x.tie || 0)) : x.miss);
  let trs = "";
  for(const x of rows){
    trs += '<tr><td>' + escHtml(x.state) + '</td>' +
      '<td style="color:var(--muted)">' + escHtml(x.action || "") + '</td>' +
      '<td class="nu">' + x.samples + '</td>' +
      '<td class="nu">' + x.hit + ' / ' + optDecided(x) + '</td>' +
      '<td class="nu ' + (x.tieRate == null ? "" : vkE(x.tieRate)) + '">' + (x.tie || 0) + '</td>' +
      '<td class="nu ' + (x.acc == null ? "" : vkBC(x.acc)) + '">' + fmtV(x.acc) + '</td>' +
      '<td class="nu ' + (x.tieRate == null ? "" : vkE(x.tieRate)) + '">' + fmtV(x.tieRate) + '</td></tr>';
  }
  const w = Array.isArray(r.weights) ? r.weights : [];
  const eCls = r.tieRate == null ? "" : vkE(r.tieRate);
  return '<div class="vcard">' +
    '<div class="vcap">匹配正确率</div>' +
    '<div class="vval' + (r.accuracy == null ? "" : " " + vkBC(r.accuracy)) + '">' + fmtV(r.accuracy) + '</div>' +
    // 口径与「特征验证」的匹配正确率（D）一致：命中 = 自家匹配度唯一最高；打平不算命中、不算判错、不进分母
    '<div class="vsub" title="命中 = 该样本的自家匹配度是全场唯一最高（并列不算命中）。无法区分 = 放弃分不开的特征后，' +
      '最高匹配度仍被 ≥2 个分类并列（或本样本可用特征被全部放弃）：既不算命中也不算判错，也不进正确率的分母。' +
      '跳过 = 归属分类在本算法参与的特征上都没有有效产物，计入不了对错。">' +
      '命中 ' + r.hit + ' / ' + optDecided(r) + '（判错 ' + miss(r) + '）' +
      (r.tie ? ' · 无法区分 ' + r.tie + ' 张（' + fmtV(r.tieRate) + '，不计入正确率）' : '') +
      (r.skipped ? ' · 跳过 ' + r.skipped + ' 张（归属分类无有效产物）' : '') + ' · 耗时 ' + vkCost(r.costMs) + '</div>' +
    '<div class="vcap" style="margin-top:8px">无法区分率</div>' +
    '<div class="vval' + (eCls ? " " + eCls : "") + '">' + fmtV(r.tieRate) + '</div>' +
    '<div class="vsub">最高匹配度被 ≥2 个分类并列（分值一样、分不出该选哪一类）的样本占比：' +
      (r.tie || 0) + ' / ' + r.samples + ' 张（分母 = 可判定样本、含无法区分；正确率的分母不含它，' +
      '故正确率 + 无法区分率不一定等于 100%）。逐张样本先放弃「分不开」的特征再重新加权，仍并列才算无法区分，' +
      '与特征验证的 E 同口径</div>' +
    (w.length ? '<div class="vsub">权重 Y：' + w.map(fmtY).join(" / ") + '</div>' : '') +
    '<details style="margin-top:8px"><summary style="cursor:pointer;font-size:12px;color:var(--muted)">查看各分类准确率与无法区分率</summary>' +
      '<div class="optTblWrap" style="max-height:32vh;margin-top:6px"><table class="optTbl"><thead><tr>' +
        '<th>分类</th><th>动作</th><th>样本</th><th>命中/能给出结果</th><th>无法区分</th><th>准确率</th><th>无法区分率</th>' +
      '</tr></thead><tbody>' + (trs || '<tr><td colspan="7" style="color:var(--muted)">无数据</td></tr>') + '</tbody></table></div>' +
    '</details>' +
  '</div>';
}

/* 右栏结论：按匹配正确率给算法排序；结果仍分不出唯一一类时给出「无法区分」的最终结论 */
function optVerdict(res){
  const all = Array.isArray(res.algos) ? res.algos : [];
  // 正确率分母已剔除无法区分样本（与特征验证的 D 同口径）：全部样本都无法区分时 accuracy = null，
  // 这类算法仍要参与「最终结论 = 无法区分」的判定，所以排序时把 null 当 −1（低于任何有结果的算法）
  const accOf = r => (r.accuracy == null ? -1 : r.accuracy);
  const list = all.filter(r => r.accuracy != null || r.tie > 0).sort((a, b) => accOf(b) - accOf(a));
  if(!list.length) return "";
  const top = list[0];
  const miss = r => (r.miss == null ? (r.samples - r.hit - (r.tie || 0)) : r.miss);
  // 最终结论 = 无法区分：最高正确率的算法下，全部可判定样本都分不出唯一一类（没一张能给出结果 → accuracy 为 null）
  if(top.accuracy == null && top.tie > 0){
    return '<div class="optcard"><h4>结论</h4><div class="ocap">' +
      '<b style="color:var(--text)">无法区分</b>：' + escHtml(top.name) + ' 下 ' + top.tie + ' / ' + top.samples +
      ' 张样本全部无法区分——最高匹配度被 ≥2 个分类并列（分值一样、分不出该选哪一类），没有任何一张能判出唯一一类' +
      '（该算法的匹配正确率因此无从计算，卡片里显示「——」）。' +
      '<br>说明这些特征分不开彼此（常见于交集图命中的只是一小块「样本间完全一致的静态区域」，别的分类截图也能整块复现）。' +
      '建议改用 <b style="color:var(--text)">独有区（-unique）</b> 或 <b style="color:var(--text)">去重均值</b> 那几档特征' +
      '（它们的「(生成成功率 A − 无法区分率 E) × 匹配正确率 D」更高），或把区分度低的特征权重 Y 调低后重跑。' +
      (res.stale ? '<br><b style="color:var(--amber)">已标注 / 汇总分析 / 特征验证的数据已变动，以上结果可能过期，建议重新计算。</b>' : '') +
      '</div></div>';
  }
  const tie = list.length > 1 && list[1].accuracy === top.accuracy;
  return '<div class="optcard"><h4>结论</h4><div class="ocap">' +
    (tie ? '各算法匹配正确率并列最高：' + fmtV(top.accuracy) + '。'
         : '匹配正确率最高：<b style="color:var(--text)">' + escHtml(top.name) + '</b> ' + fmtV(top.accuracy) +
           '（命中 ' + top.hit + ' / 能给出结果 ' + optDecided(top) + ' · 判错 ' + miss(top) + '）。') +
    (top.tie ? '<br>另有 <b style="color:var(--text)">' + fmtV(top.tieRate) + '</b> 的样本<b style="color:var(--text)">无法区分</b>（'
      + top.tie + ' / ' + top.samples + ' 张）：放弃有问题的特征后最高匹配度仍被 ≥2 个分类并列，既不算命中也不算判错，'
      + '也不进匹配正确率的分母（正确率分母 = 命中 + 判错）。'
      + (top.tieRate >= 20 ? '<br><b style="color:var(--amber)">无法区分率偏高（≥20%）：建议改用独有区（-unique）或去重均值特征，或把区分度低的特征权重 Y 调低后重跑。</b>' : '')
      : '') +
    '<br>排序：' + list.map(r => escHtml(r.name) + ' ' + fmtV(r.accuracy) + '（无法区分 ' + fmtV(r.tieRate) + '）').join('　＞　') +
    (res.stale ? '<br><b style="color:var(--amber)">已标注 / 汇总分析 / 特征验证的数据已变动，以上结果可能过期，建议重新计算。</b>' : '') +
  '</div></div>';
}

/* 每轮轮询刷新：meta / 禁用态 / 右栏进度与结论 / 各算法结果（不重建输入） */
function optRender(){
  const j = OPT;
  if(!j) return;
  const algos = Array.isArray(j.algos) ? j.algos : [];
  const run = !!j.running;
  const t = j.task || null;
  const res = (j.ready && j.result && j.result.finished && !j.result.error) ? j.result : null;
  const fatal = (j.result && j.result.error) ? j.result.error : null;

  const tuning = run && t && t.mode === "tune";
  const meta = $("optMeta");
  if(meta) meta.textContent = "样本 " + j.samples + " 张 · 分类 " + j.groups + " 个 · " +
    (run ? (tuning ? "自动调整参数中" : "验证运行中") : ("算法 " + algos.length + " 个" + (j.ready ? "" : "（未计算：缺少特征验证结果）")));
  // 运行中禁用输入；单一特征算法的权重固定 1（结构里本来就带 disabled），这里不能把它解除
  document.querySelectorAll("#optPane .optYin, #optPane .optYen").forEach(el => {
    const a = optAlgo(el.dataset.a);
    el.disabled = run || !a || a.features.length <= 1;
  });

  const taskBox = $("optTask"), fill = $("optFill"), tStat = $("optTaskStat"), tTxt = $("optTaskTxt"), stat = $("optStat");
  const err = (t && t.error) || fatal;
  if(run && t && !t.finished){
    taskBox.style.display = "block";
    // 精确到张：外层（特征 / 算法）跑到第几项 + 本项内已比对张数 + 最近一张文件名 + 已耗时（后端按张回报）
    const sn = Math.max(0, Number(t.totalSamples) || 0);
    const isMat = t.stage === "逐特征比对矩阵";
    const tuneStage = t.mode === "tune" && t.stage === "自动调整参数";
    let st = "阶段：" + (t.stage || "准备中");
    if(t.total) st += "　" + Math.min(t.done || 0, t.total) + "/" + t.total + " 个" + (isMat ? "特征" : (tuneStage ? "权重" : "算法"));
    // 自动调整参数阶段：当前是「哪个算法 · 哪个特征」的第几次随机尝试、这次随机到的 Y 是多少
    if(tuneStage && t.cur) st += "（" + t.cur + (t.tuneKind ? " · " + vkInfo(t.tuneKind).name : "")
      + (t.trial ? " · 第 " + t.trial + "/" + (t.trials || 10) + " 次随机 Y=" + fmtY(t.trialY) : "") + "）";
    else if(t.cur) st += "（" + (isMat ? vkInfo(t.cur).name : t.cur) + "）";
    if(sn) st += "　第 " + Math.min(t.processed || 0, sn) + "/" + sn + " 张";
    tStat.textContent = st;
    // 进度条走「合计张数」（跨特征 / 跨算法连续、每张都推进，不再等整张矩阵跑完才跳一格）；拿不到张数时退回外层项占比
    const pct = t.allTotal
      ? Math.min(t.allDone || 0, t.allTotal) / t.allTotal * 100
      : (t.total ? Math.min(t.done || 0, t.total) / t.total * 100 : 0);
    fill.style.width = Math.round(pct) + "%";
    const sec = Math.max(0, Math.round((Number(t.elapsedMs) || 0) / 1000));
    tTxt.textContent = (t.sample ? "正在比对 " + t.sample : "正在准备（扫描样本 / 分类产物）…")
      + (tuneStage && t.baseAcc != null ? " · 该权重当前基线 正确率 " + fmtV(t.baseAcc < 0 ? null : t.baseAcc)
          + " / 无法区分 " + fmtV(t.baseTie) : "")
      + (tuneStage && t.bestAcc != null ? " · 已找到最好 正确率 " + fmtV(t.bestAcc < 0 ? null : t.bestAcc)
          + " / 无法区分 " + fmtV(t.bestTie) : "")
      + (sec ? " · 已耗时 " + durTxt(sec) : "");
    if(stat) stat.textContent = (t.mode === "tune"
        ? "正在后台自动调整参数：逐权重在 0~1 之间随机试 10 次（步进 0.001），只有匹配正确率上升、或无法区分率下降才采纳，"
          + "新权重保存到 classify/opt-weights.json。\n（" + st + "）"
        : "正在后台验证全部算法的分类准确率…（" + (t.stage || "准备中")
          + (sn ? " · 第 " + Math.min(t.processed || 0, sn) + "/" + sn + " 张" : "") + "）")
      + "\n建议先停掉特征验证 / 执行模式自动识别，避免两者互相争抢 CPU 与磁盘 IO。";
  }else{
    taskBox.style.display = "none";
    fill.style.width = "0%";
    if(stat){
      if(err) stat.textContent = "最近一次验证失败：" + err;
      else if(!j.ready) stat.textContent = (j.verify && j.verify.running)
        ? "特征验证正在运行，请等它结束后再回到本视图。"
        : "算法由特征验证结果组合而来：请先到「特征验证」视图完成一次验证。\n（当前已验证 " + ((j.verify && j.verify.fresh) || 0) + " / " + ((j.verify && j.verify.total) || 0) + " 个特征）";
      else if(res && Array.isArray(res.algos) && res.algos.length){
        const best = res.algos.filter(r => r.accuracy != null).sort((a, b) => b.accuracy - a.accuracy)[0];
        stat.textContent = "上次验证完成" + fmtCostSuffix(Number(res.costMs)) + "：样本 " + res.samples + " 张 · 分类 " + res.groups + " 个" +
          (best ? "\n最高匹配正确率：" + best.name + " " + fmtV(best.accuracy) + "（命中 " + best.hit + "/" + optDecided(best)
            + " · 判错 " + (best.miss == null ? (best.samples - best.hit - (best.tie || 0)) : best.miss)
            + (best.tie ? " · 无法区分 " + best.tie + "（" + fmtV(best.tieRate) + "）" : "") + "）" : "") +
          // 结果来自 summary/opt-result.json（完整缓存）：三处数据都没变就直接用上次的，不必重算
          (j.cached ? "\n已从 summary/" + (j.cacheFile || "opt-result.json") + " 恢复上次结果（数据没变即可直接用）。" : "") +
          (res.stale ? "\n注意：已标注 / 汇总分析 / 特征验证的数据已变动，结果可能过期，建议重新计算。" : "") +
          (j.tune && j.tune.finished ? "\n自动调整参数上一次完成" + fmtCostSuffix(Number(j.tune.costMs)) + "：采纳 "
            + (j.tune.improved || 0) + " 处权重调整（共试探 " + (j.tune.weights || 0) + " 个权重），权重文件 "
            + (j.tune.file || "") : "") +
          // 特征选择 + 权重数值始终另存一份最新的（后续功能 / 开发验证直接读，不必解析界面状态）
          "\n特征选择与权重数值快照：summary/" + (j.snapshotFile || "opt-weights.json") + "（每次跑完覆写最新的）。";
      }else stat.textContent = "已组合 " + algos.length + " 个算法，点右上角「验证所有算法」开始。";
    }
  }

  // 后端保存的权重变了（自动调整参数采纳了新值 / 手改了权重文件）：把新值同步回权重草稿与输入框
  const wSig = algos.map(a => a.id + ":" + ((a.weights || []).join(","))).join(";");
  if(wSig !== optWSig){
    const first = optWSig === "";
    let dirty = false;
    for(const a of algos){
      if(a.features.length <= 1) continue;      // 单一特征算法固定 1，不跟着权重文件走
      const d = optY[a.id];
      if(!d) continue;
      const w = Array.isArray(a.weights) ? a.weights : [];
      let changed = false;
      for(let i = 0; i < d.vals.length; i++){
        const y = Number(w[i]);
        const en = (w[i] == null || isNaN(y)) ? true : y > 0;
        const v = (w[i] != null && !isNaN(y) && y > 0) ? Math.round(y * 100) / 100 : 1;
        if(d.en[i] !== en || d.vals[i] !== v){ d.en[i] = en; d.vals[i] = v; changed = true; }
      }
      if(changed){ syncOptRow(a.id); dirty = true; }
    }
    optWSig = wSig;
    if(dirty && !first) toast("已应用后端保存的权重 Y（自动调整参数 / 权重文件）。", "ok");
  }

  const resSig = (j.ready ? "R|" : "P|") + (res
    ? res.algos.map(r => [r.id, r.accuracy, r.tieRate, r.hit, r.tie, r.miss, r.decided, r.samples, r.skipped, r.costMs, (r.weights || []).join(",")].join("|")).join(";") + (res.stale ? "|stale" : "")
    : "none");
  if(resSig !== optResSig){
    optResSig = resSig;
    for(const a of algos){
      const box = $("optRes-" + a.id);
      if(!box) continue;
      const r = res ? (res.algos || []).find(x => x.id === a.id) : null;
      box.innerHTML = r ? optResCard(r)
        : '<div class="ocap" style="margin:0">未计算' + (j.ready
            ? '（点右上角「验证所有算法」）。如需调整特征权重 Y，改完再点验证。'
            : '：先到「特征验证」视图完成一次验证，算法、特征与基础分 X 会自动组合。') + '</div>';
    }
  }
  const ver = $("optVerdict");
  if(ver) ver.innerHTML = (j.tune ? optTuneCard(j.tune) : "") + (res ? optVerdict(res) : "");
}

/* 启动即预取算法个数（顶栏「算法调优(N)」不依赖先进本视图）：特征验证未就绪时后端同样下发全部算法骨架，
   所以未验证时也有数字；只在个数变化时写按钮 */
async function optPrefetch(){
  if(appMode !== "mark" || optCntBusy) return;
  optCntBusy = true;
  try{
    const r = await fetch("/api/optimize/status", { cache:"no-store" });
    if(r.ok){
      const j = await r.json();
      const n = j && Array.isArray(j.algos) ? j.algos.length : null;
      if(n != null && optCnt !== n){ optCnt = n; setSegState(); }
    }
  }catch(_){}
  optCntBusy = false;
}

async function optPoll(){
  if(appMode !== "mark" || FILTER !== "opt") return;
  if(optBusy) return;
  optBusy = true;
  let j = null;
  try{
    const r = await fetch("/api/optimize/status", { cache:"no-store" });
    if(!r.ok) throw new Error("HTTP " + r.status);
    j = await r.json();
  }catch(e){ optBusy = false; return; }
  optBusy = false;
  if(FILTER !== "opt") return;
  const prevRun = OPT ? !!OPT.running : false;
  OPT = j;
  const nAlgo = Array.isArray(j.algos) ? j.algos.length : null;
  if(nAlgo != null) optCnt = nAlgo;    // 顶栏「算法调优(N)」：下面的 renderOptList → setSegState 会写进按钮
  // 已标注 / 汇总分析 / 特征验证的数据一有变动（指纹或算法口径变了）就提示一次重新计算；数据回一致后再变动会再提示
  const optStale = !!((j.result && j.result.finished && j.result.stale) || (j.tune && j.tune.stale));
  if(optStale && j.fp && j.fp !== OPT_STALE_SEEN){
    OPT_STALE_SEEN = j.fp;
    toast("已标注 / 汇总分析 / 特征验证的数据有变动，算法调优结果已过期，请重新计算。", "warn");
  }else if(!optStale){
    OPT_STALE_SEEN = "";
  }
  optEnsureMain();
  optRender();
  renderOptList();
  optSyncRunBtn();
  if(prevRun && !j.running){
    const err = j.task ? j.task.error : (j.result ? j.result.error : null);
    const isTune = !!(j.task && j.task.mode === "tune");
    if(err) toast((isTune ? "自动调整参数中断：" : "算法验证中断：") + err, "err");
    else if(isTune) toast("自动调整参数已完成" + fmtCostSuffix(Number(j.task && j.task.costMs)) + "：采纳 "
      + ((j.tune && j.tune.improved) || 0) + " 处权重调整，新权重已保存并应用到界面。", "ok");
    else toast("算法验证已完成" + fmtCostSuffix(Number(j.task && j.task.costMs)) + "，可在主图区查看各算法分类准确率。", "ok");
  }
}

/* 收集界面上的权重草稿 Y（未启用 / 无特征 → 0；单一特征算法固定 1，与后端强制口径一致）：{ 算法id: [Y...] } */
function optCollect(){
  const out = {};
  for(const a of ((OPT && OPT.algos) || [])){
    const d = optY[a.id] || optYof(a);
    const single = a.features.length <= 1;
    out[a.id] = a.features.map((f, i) => single ? 1 : (d.en[i] ? d.vals[i] : 0));
  }
  return out;
}

/* 运行按钮可用性：未就绪 / 运行中 / 某算法一个特征都没启用时禁用；
   「自动调整参数」更严：必须「验证所有算法」已经跑完且结果没过期（后端 tunable） */
function optSyncRunBtn(){
  const b = $("optRunBtn");
  if(!b) return;
  const algos = (OPT && Array.isArray(OPT.algos)) ? OPT.algos : [];
  const run = !!(OPT && OPT.running);
  const ready = !!(OPT && OPT.ready) && algos.length > 0;
  let any = false;
  for(const a of algos){ const d = optY[a.id]; if(d && d.en.some(Boolean)) any = true; }
  b.disabled = run || !ready || !any;
  b.title = !ready ? "请先在「特征验证」视图完成验证（算法由验证结果组合而来）"
    : !any ? "每个算法至少要启用一个特征"
    : "按当前特征组合与权重 Y 验证全部算法的分类准确率（classify/ 全部已标注原图 × 全部分类）；运行中不可再次启动";
  const ab = $("optAutoBtn");
  if(!ab) return;
  ab.disabled = run || !(OPT && OPT.tunable);
  ab.title = run ? "正在跑任务，等它结束"
    : !ready ? "请先在「特征验证」视图完成验证（算法由验证结果组合而来）"
    : !(OPT && OPT.tunable) ? "请先点「验证所有算法」并等它跑完（结果要能对上当前的样本 / 产物），之后才能自动调整参数"
    : "对每个可调的权重 Y 在 0~1 之间随机试 10 次（步进 0.001）：只要匹配正确率上升、或无法区分率下降就采纳，" +
      "并把新权重保存到 classify/opt-weights.json（界面权重框随更新）；单一特征算法的权重固定 1、不参与调整";
}

async function optStartRun(){
  if(OPT && OPT.running) return;
  if(!OPT || !OPT.ready){ toast("请先在「特征验证」视图完成验证。", "err"); return; }
  try{
    const r = await fetchT("/api/optimize/start", { method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ weights: optCollect() }) }, 9000);
    const j = r && r.ok ? await r.json().catch(()=>null) : null;
    if(j && j.started) toast("开始验证全部算法的分类准确率…", "ok");
    else toast("启动失败：" + ((j && j.error) || "请求失败"), "err");
  }catch(e){ toast("启动失败：请求异常", "err"); }
  optPoll();
}
$("optRunBtn").addEventListener("click", optStartRun);

/* 把某算法的权重草稿写回界面控件（同步后端保存的权重时用：值 / 勾选 / 生效 X × Y 一起写） */
function syncOptRow(id){
  const d = optY[id];
  if(!d) return;
  for(let i = 0; i < d.vals.length; i++){
    const inp = document.querySelector('#optPane .optYin[data-a="' + id + '"][data-i="' + i + '"]');
    if(inp) inp.value = d.vals[i];
    const cb = document.querySelector('#optPane .optYen[data-a="' + id + '"][data-i="' + i + '"]');
    if(cb) cb.checked = d.en[i];
    const cell = $("optEff-" + id + "-" + i);
    if(cell) cell.textContent = fmtX(d.en[i] ? d.vals[i] * optXof(id, i) : 0);
  }
}

/* 「自动调整参数」结果卡（右栏结论上方）：试探了多少权重、采纳了几处、每个算法的前后对比、落盘位置 */
function optTuneCard(t){
  const rows = Array.isArray(t.algos) ? t.algos : [];
  let lines = "";
  for(const x of rows){
    const before = (Array.isArray(x.before) ? x.before : []).map(fmtY);
    const after = (Array.isArray(x.after) ? x.after : []).map(fmtY);
    lines += '<br>· <b style="color:var(--text)">' + escHtml(x.name || "") + '</b>：正确率 ' + fmtV(x.accBefore) +
      ' → ' + fmtV(x.accAfter) + ' · 无法区分率 ' + fmtV(x.tieBefore) + ' → ' + fmtV(x.tieAfter);
    if(!x.tunable) lines += '（单一特征：权重固定 1，不参与调整）';
    else if(x.changed) lines += '　权重 Y ' + before.join(" / ") + ' → <b style="color:var(--text)">' + after.join(" / ") + '</b>';
    else lines += '　10 次随机都没改善，权重 Y 保持 ' + after.join(" / ");
  }
  return '<div class="optcard"><h4>自动调整参数</h4><div class="ocap">' +
    (t.improved ? '共采纳 <b style="color:var(--text)">' + t.improved + '</b> 处权重调整' : '本轮没有找到更好的权重') +
    '（共试探 ' + (t.weights || 0) + ' 个权重 × 每个 10 次随机；口径 = 匹配正确率上升、或无法区分率下降）' +
    (t.costMs != null ? '，耗时 ' + durTxt(Math.max(0, Math.round((Number(t.costMs) || 0) / 1000))) : '') + '。' +
    (t.file ? '<br>权重已保存到 <b style="color:var(--text)">' + escHtml(t.file) + '</b>（可随时手删，删了回到默认全 1）。' : '') +
    '<br>特征选择与权重数值快照：<b style="color:var(--text)">summary/' + escHtml((OPT && OPT.snapshotFile) || "opt-weights.json") +
      '</b>（每次跑完覆写一份最新的，供后续功能读取）。' +
    lines +
    (t.stale ? '<br><b style="color:var(--amber)">已标注 / 汇总分析 / 特征验证的数据已变动，以上权重只对当时的特征组合有意义。</b>' : '') +
  '</div></div>';
}

/* 启动「自动调整参数」（后端只在「验证所有算法」跑完且结果没过期时才受理） */
async function optAutoStart(){
  if(OPT && OPT.running) return;
  if(!OPT || !OPT.tunable){ toast("请先点「验证所有算法」并等它跑完，再自动调整参数。", "err"); return; }
  try{
    const r = await fetchT("/api/optimize/auto", { method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ weights: optCollect() }) }, 9000);
    const j = r && r.ok ? await r.json().catch(()=>null) : null;
    if(j && j.started) toast("开始自动调整参数：逐权重在 0~1 之间随机试 10 次，正确率上升或无法区分率下降才采纳…", "ok");
    else toast("启动失败：" + ((j && j.error) || "请求失败"), "err");
  }catch(e){ toast("启动失败：请求异常", "err"); }
  optPoll();
}
$("optAutoBtn").addEventListener("click", optAutoStart);

/* ---------------- 自动刷新 ---------------- */
const POLL_MS = 10000;   // 后台每 10 秒悄悄同步一次列表
function listSig(arr){ return arr.map(i => [i.name,i.marked,i.state,i.action,i.left,i.top].join("|")).join("\n"); }
async function refreshSilent(){
  let arr;
  try{ arr = await fetchAllSafe(); }catch(e){ return false; }
  if(FILTER === "think" || FILTER === "verify" || FILTER === "opt") return true;   // 等待期间已切去分析视图：让对应视图自行刷新，不抢着重绘普通列表
  if(listSig(ALL) === listSig(arr)) return true;   // 无实质变化则不重绘，避免打扰
  ALL = arr;
  try{ DEF = await fetchDefs(); }catch(_){}
  rebuildStates();
  renderList();
  updateNavButtons();
  // 列表从“全部完成”空态补进新图时自动载入第一张，占位提示随之消失
  if(!curName){
    const L = listNow();
    if(L.length) selectTarget(L[0].name);
  }
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
  if(FILTER === "verify"){ vkPoll(); return; }  // 特征验证：轮询 A/B 状态与验证任务进度
  if(FILTER === "opt"){ optPoll(); return; }    // 算法调优：轮询特征组合算法与验证进度
  if(vkCnt == null) vkPrefetch();               // 启动预取失败兜底：计数补上后自然不再发
  if(optCnt == null) optPrefetch();             // 同理，「算法调优(N)」的算法个数
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
  }else if(FILTER === "verify"){
    vkPoll();
  }else if(FILTER === "opt"){
    optPoll();
  }else{
    refreshSilent();
  }
});

/* ---------------- 服务端版本检测：后端重新打包/重启后自动刷新页面 ---------------- */
const META_MS = 2000;      // 每 2 秒探一次：既做版本/存活探测，也借 savedSeq 感知“新截图已保存”以即时刷新列表
const META_DOWN_LIMIT = 5; // 连续这么多次探测不到后端（单次最多等 3s 超时）即判定“失联”，弹“是否结束”确认
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
    const r = await fetchT("/api/app/meta?shotAfter=" + lastShotLogSeq, { cache:"no-store" }, 3000);
    if(!r.ok) return;
    const j = await r.json();
    metaDown = 0;                        // 探测成功：清零连续失败计数
    if(lostAsking || metaGone){
      // 失联后服务已恢复：自动收起失联确认并复位状态（用户点过“取消”等待的同样在此放行）
      metaGone = false;
      if(lostAsking){ hideLostAsk(); toast("后端服务已恢复连接。", "ok"); }
    }
    // 截图 resize 持续不达标 → 后端已自动暂停：弹窗提示并同步按钮态（仅提示一次，用户 resume 后可再次提示）
    const stopReason = j && j.captureStopReason;
    if(stopReason && stopReason !== lastStopReasonShown){
      lastStopReasonShown = stopReason;
      if(!capPaused){ capPaused = true; renderCapBtn(); }   // 后端已暂停：按钮恢复为「自动采集」
      showCapStopModal(stopReason);
    }
    // 截图结果历史（shotLog）：后端按发生顺序保留每一轮「保存 / 差异过小丢弃」结果，本请求按 seq 增量拉取。
    // 每条都写入历史日志（showShotTip / pushLog）；只把最新一条作右下角单条替换式轻提示，
    // 首次全量回填积压时不逐条闪屏（旧页面无 shotLog 字段时静默跳过，兼容旧后端）
    const shotLog = Array.isArray(j && j.shotLog) ? j.shotLog : [];
    if(shotLog.length){
      const pctTxt = v => {
        let p = (v != null ? v : (capDiffThreshold || 0));
        if(Number.isInteger(p)) return String(p);           // 整数直显（如阈值 5 → "5"）
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
          : "当前画面与" + refWho + "仅 " + pctTxt(s.pct) + "% 像素点不同（≤ " + pctTxt(s.threshold) + "% 阈值，视为同一画面），未保存";
        if(i === shotLog.length - 1) showShotTip(txt, saved ? "ok" : "skip");   // 最新一条：右下角轻提示（内部已入日志）
        else pushLog(txt, saved ? "ok" : "skip", Number(s.at) || undefined);    // 轮询间隙的中间条：仅入历史日志
      }
      lastShotLogSeq = Number(shotLog[shotLog.length - 1].seq) || lastShotLogSeq;
    }
    // 后端重启后 seq 会从 1 重新计数（历史已清空）：本页基线若已越过它则说明计数跳变，下一轮改为全量回填，避免提示静默中断
    const maxSeq = Number(j && j.shotMaxSeq) || 0;
    if(maxSeq > 0 && lastShotLogSeq > maxSeq) lastShotLogSeq = -1;
    // 启动历史重复清理结果：后端每次启动按两个启用阈值中较低者（默认 min(5, 0.5) = 0.5%）逐像素比对重扫
    // capture/ + classify/ 全部截图，不一致像素占比 ≤ 阈值即删（近似但不重复的画面一律保留）。
    // 不论是否删除了图片都右下角提示一次清理完成
    // 启动历史重复清理进行态（startupDedup）：开始 → 一条「开始检查」消息；进行中 → 一条一直刷新的进度消息
    // （已判定 / 待判定张数 + 当前文件 + 逐秒走动的已耗时，同批量任务的「正在第 1 轮…（已耗时 29 秒）」）；
    // 结束 → running=false 撤掉进度消息，结果提示由下面的 startupDedupNotice 给出
    const dp = j && j.startupDedup;
    if(dp && dp.at){
      const dpAt = Number(dp.at) || 0;
      if(dp.running){
        if(dpAt !== lastDedupProgAt){                 // 首次见到本次启动的清理：先提示一条「开始检查」
          lastDedupProgAt = dpAt;
          showShotTip("启动重复清理：开始检查 capture/ + classify/ 的历史截图重复"
            + "（逐张全尺寸逐像素比对，与保留图不一致像素点占比 ≤ 阈值即视为重复删除；"
            + "文件名 + 修改时间都没变过的组合直接复用上次的比对结果、不再重复比对）…", "");
        }
        dedupProg = { at:dpAt, done:Number(dp.done)||0, total:Number(dp.total)||0,
                      current:dp.current || "", compared:Number(dp.compared)||0, reused:Number(dp.reused)||0 };
        dedupProgTick();                              // 立即刷一次（不必等下一个 1 秒 tick）
      }else if(dedupProg){
        dedupProg = null;                             // 扫描结束：撤掉进度消息（结果由 startupDedupNotice 提示）
        taskTip(null);
      }
    }
    const dedup = j && j.startupDedupNotice;
    if(dedup && dedup.at && Number(dedup.at) !== lastDedupNoticeAt){
      lastDedupNoticeAt = Number(dedup.at);
      // 本次清理阈值（%）：整数直显、非整数去尾零；旧后端无该字段时省略判据不写
      const thr = Number(dedup.threshold);
      const thresholdTxt = isFinite(thr) && thr > 0
        ? "按不一致像素占比 ≤ " + (Number.isInteger(thr) ? String(thr) : String(Math.round(thr * 100) / 100)) + "% "
        : "";
      // 判定量：本次逐像素比对次数 + 复用 dedup-cache.json 上次结果的次数 + 其中最低的不一致像素占比
      // （= 最接近重复的一对还差多少，含复用的值）；旧后端无这些字段、或没有可比对的同尺寸图时省略该段不写
      const compared = Number(dedup.compared) || 0;
      const reused = Number(dedup.reused) || 0;
      const minDiff = Number(dedup.minDiff);
      const judgeTxt = dedupCmpTxt(compared, reused);
      let cmpTxt = "";
      if(judgeTxt){
        const minTxt = (isFinite(minDiff) && minDiff >= 0)
          ? "，最低不一致 " + (Number.isInteger(minDiff) ? String(minDiff) : String(Math.round(minDiff * 100) / 100)) + "%"
          : "";
        cmpTxt = "（" + judgeTxt + minTxt + "）";
      }
      // 耗时（后端实际重扫毫秒数）；旧后端无该字段时静默不加
      const costTxt = fmtCostSuffix(Number(dedup.costMs) || 0);
      const scanned = Number(dedup.scanned) || 0;
      const msg = (dedup.removed > 0)
        ? "启动重复清理：" + thresholdTxt + "重扫 " + scanned + " 张，删除重复 " + dedup.removed + " 张" + cmpTxt + costTxt
        : "启动重复清理：" + thresholdTxt + "重扫 " + scanned + " 张，未发现重复图片" + cmpTxt + costTxt;
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
      toast("检测到服务端代码已更新。当前标注尚未保存：保存、清除标记或删除后，会自动刷新加载新版页面。", "ok");
    } else {
      toast("检测到服务端代码已更新，正在刷新页面…", "");
      allowReloadClose = true;               // 放行 beforeunload，避免代码刷新被关闭确认拦截
      setTimeout(()=> location.reload(), 500);
    }
  }catch(e){
    /* 探测失败（含单次超时）：短时失败可能是服务重启间隙；连续失败 = 后端失联（已停/OOM 退出/垂死）→ 问是否结束 */
    if(exiting || metaGone) return;
    if(++metaDown >= META_DOWN_LIMIT){
      metaGone = true;
      if(document.hidden){ needExitUI = true; return; }  // 后台先不打扰，回到前台再弹确认
      showLostAsk();
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
  if(needExitUI){                          // 后台已判定后端失联：回到页面立即弹“是否结束”确认
    needExitUI = false;
    showLostAsk();
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
    toast("当前标注尚未保存：请先「保存」或「清除标记」，再切换到执行模式。", "err");
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
let execShownStored = false;    // 当前画面是否已存入过（分类样本 / 待标注截图）：存入任一去处后其它存入按钮联动置灰，防同一帧重复存入
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
let execSkipSig = "";   // 最近一轮「未参与比对」的跳过名单签名：名单变化才 toast 提醒一次，避免识别轮询刷屏

function renderExecAll(){
  const j = execLatest;
  if(!j) return;
  const meta = $("execMetaChip");
  if(meta) meta.textContent = (j.imageWidth > 0 && j.imageHeight > 0)
      ? (j.imageWidth + "×" + j.imageHeight) : "—";

  // 尚无可展示画面且不是已确认的失败：识别一轮在途，或后端只是“还没产生过结果”（占位快照）→ 属等待态
  const idleWait = execPending || String(j.error || "").indexOf("尚未产生识别结果") === 0;

  // 「匹配分类」= 识别命中的分类标注（state）：动作 / 点击坐标都取自该分类（summary/<分类>/info.json）。
  // 产物目录常规与分类标注同名（一个分类 = 一个比对分组）；仅在二者确不相同（历史数据同分类
  // 多动作遗留的“<分类>_<action>”目录）时把目录名挂到悬停提示上，不放行宽、不在面板重复出现。
  // 匹配分类 = 差异分值最低的最近似分类；分值仅作参考、不设识别阈值，不再区分“已识别/未识别”
  let matchedTxt = "—", matchedCls = "";
  if(!idleWait && !(j.error && j.imageWidth <= 0 && !j.windowFound)){
    if(j.state){
      matchedTxt = j.state; matchedCls = "";
    }
  }
  const em = $("#execMatched");
  if(em) em.title = (matchedTxt !== "—" && j.matchedSample && j.matchedSample !== j.state)
      ? ("产物目录 " + j.matchedSample) : "";
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
    const skip = j.skippedGroups || {};
    const skipNames = Object.keys(skip);
    execSetVal("execSamples",
        "已比对 " + (j.scannedSamples || 0) + " / 全部 " + j.totalSamples + " 个分类"
          + (skipNames.length ? "（跳过 " + skipNames.length + " 个）" : ""),
        j.scannedSamples > 0 && j.totalSamples > j.scannedSamples ? "err" : "");
    const se = $("execSamples");
    if(skipNames.length){
      // 悬停可看「哪个分类、为什么」被跳过；名单与上轮不同（新出现/已变化/恢复）时 toast 提醒一次
      se.title = "未参与比对的分组（目录 → 原因）：\n"
        + skipNames.map(n => "· " + n + "：" + (skip[n] || "未知")).join("\n");
      const sig = skipNames.join("|");
      if(execSkipSig !== sig){
        execSkipSig = sig;
        toast("有 " + skipNames.length + " 个分类未参与比对：" + skipNames.join("、")
            + "。悬停「比对分组」行查看原因（产物补齐后自动恢复）", "warn");
      }
    } else {
      se.title = "";
      execSkipSig = "";
    }
  } else {
    execSetVal("execSamples", "—", "");
    const se = $("execSamples"); if(se) se.title = "";
    execSkipSig = "";
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
    execShownStored = false;      // 新画面：重新允许存入（分类样本 / 待标注截图）
    execLoadFrame();
  } else if(j.imageWidth > 0){
    renderExecMarkers();      // 同一帧：只刷新标记
    execFitImage();           // 同一帧：按当前画面区重排一次，修正进入前窗口变化留下的过期缩放
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

/* 「存入待标注」可用态：有当前画面即可点（自动识别循环中也保持可用，画面随时可另存为待标注）；
   当前画面已存入过任一去处（execShownStored）时置灰为「已存入」，与候选行的「存入分类」联动 */
function renderExecSaveCap(j){
  const b = $("execSaveCap"); if(!b) return;
  if(!b.dataset.t0) b.dataset.t0 = b.title;      // 记住 HTML 里的原始说明，恢复用
  const can = !!(j && j.imageWidth > 0 && j.imageHeight > 0);
  if(execShownStored){
    b.disabled = true;
    b.textContent = "已存入";
    b.title = "当前画面已存入（待标注截图或某分类样本）；「立即识别」出新画面后可再次存入";
  } else {
    b.disabled = !can;
    b.textContent = "存入待标注";
    b.title = b.dataset.t0;
  }
}

/* 把当前画面另存为 capture/ 原始截图（未标注）：切到「标注模式 → 未标注」即可定位并按正常流程精确标注 / 修正坐标（自动识别中也允许保存） */
async function execSaveToCapture(){
  const b = $("execSaveCap");
  if(!b || b.disabled) return;
  const at0 = execShownAt;
  b.disabled = true;
  b.textContent = "保存中…";
  const j = await execGet("/api/execute/save-to-capture", { method:"POST" });
  if(j && j.ok){
    if(execShownAt === at0) execShownStored = true;   // 同帧保存成功：候选「存入分类」联动置灰
    toast("已把当前画面存入 capture/（" + j.name + "）。切到「标注模式 → 未标注」即可定位并精确标注（含匹配动作与关注点坐标）。", "ok");
  } else if(j && j.kind === "dup"){
    // 与某张历史画面差异 ≤ 手动阈值被拦截（非系统错误）：用琥珀「跳过」样式提示，文案与重复参考由后端给出
    toast(j.message || "当前画面与某张已保存截图几乎重复（不一致像素占比 ≤ 手动阈值），本次未另存", "skip");
  } else {
    toast("保存失败：" + ((j && j.message) || "接口不可用"), "err");
  }
  if(execShownAt === at0){                           // 仍是同一帧：立即刷新两组存入按钮，不等下轮轮询
    renderExecSaveCap(execLatest);
    renderExecCandidates((execLatest && execLatest.candidates) || []);
  }
}

function renderExecCandidates(list){
  const box = $("execCandidates"); if(!box) return;
  box.innerHTML = "";
  if(!list || !list.length){
    box.innerHTML = '<div class="hint">暂无可用比对分组：请在标注模式为每个分类保存 ≥1 张同尺寸样本，并到「汇总分析」生成对照图（汇总分析完成即可参与识别）。</div>';
    return;
  }
  const done = execShownStored;
  list.forEach((it, i) => {
    const row = document.createElement("div");
    row.className = "cand" + (i === 0 ? " top" : "");
    const isRaw = !!(it && it.raw === true);   // 「按已分类原图匹配」直比行：来源是某张已分类原图而非对照图，无各图分值明细；仍可把当前画面存入其命中分类（后端 Candidate 字段名 raw）
    const stateTxt = execEsc(it.state || "—");
    const diffTxt = (typeof it.diffPercent === "number" && it.diffPercent >= 0) ? it.diffPercent.toFixed(2) + "%" : "—";
    if(it.matchedFile && it.matchedFile !== it.state){
      row.title = isRaw ? "已分类原图 " + it.matchedFile + "（与当前画面直比命中）" : it.matchedFile;   // 来源文件名只挂悬停提示，不占行宽
    }
    row.innerHTML = (isRaw ? '<span style="color:var(--green)">【按已分类原图匹配】</span> ' : "") +
                    '<span class="cst">' + stateTxt + "</span>" +
                    '<span class="cd">' + diffTxt + "</span>";
    if(!isRaw && Array.isArray(it.kinds) && it.kinds.length){
      const vbtn = document.createElement("button");
      vbtn.type = "button";
      vbtn.className = "mbtn";
      vbtn.textContent = "详细分值";
      vbtn.title = "查看该分类各对照图（基础图及独有区图，另含按关注点生成的注意区交集图与点击分类按点击点生成的点击区交集图）各自的不匹配点占比分值";
      vbtn.addEventListener("click", () => openKindScores(it));
      row.appendChild(vbtn);
    }
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "mbtn";
    btn.disabled = done;
    btn.textContent = done ? "已存入" : "存入分类";
    btn.title = done
      ? "当前画面已存入（待标注截图或某分类样本）；重新「立即识别」出新画面后可再次存入"
      : (isRaw
          ? "把当前画面登记为「" + stateTxt + "」的样本（其与已分类原图直比命中的分类），下次识别会优先参考它"
          : "把当前画面登记为「" + stateTxt + "」的样本，下次识别会优先参考它");
    btn.addEventListener("click", () => execQuickMark(it.state, btn));
    row.appendChild(btn);
    box.appendChild(row);
  });
}

/* ---- 各对照图分值明细弹层：某候选分类 summary 产物目录里各张对照图（15 基础图 + 15 -unique 独有区图 + 12 张注意区交集图 + 点击分类 12 张点击区交集图）的分值 ---- */
function openKindScores(it){
  if(!it || !Array.isArray(it.kinds) || !it.kinds.length){
    toast("该候选缺少对照图分值明细（未参与比对）", "err");
    return;
  }
  if($("kindScoreModal")) $("kindScoreModal").remove();
  // kind 短名 / 磁盘文件名 / 块网格判断全部由后端下发的 kind 元数据派生（不再逐 kind 列举）
  const metaOf = kind => KIND_META.find(m => m.kind === kind) || null;
  const rows = it.kinds.map(ks => {
    const m = metaOf(ks.kind);
    const nm = m ? vkInfo(ks.kind).name : ks.kind;   // 短名与特征验证列表同一来源（vkInfo），全站统一
    const isBlock = !!(m && m.block > 1);
    const grid = (typeof ks.w === "number" && ks.w > 0 && typeof ks.h === "number" && ks.h > 0)
        ? (isBlock ? "块网格 " : "产物尺寸 ") + ks.w + "×" + ks.h : "—";
    const score = (typeof ks.score === "number" && ks.score >= 0)
        ? '<b style="color:var(--green)">' + ks.score.toFixed(2) + "%</b>"
        : '<span style="color:#778" title="该图异常无分：产物缺失/解码失败或比对口径不符（产物齐全时不会出现），不参与所在族（A~E）的族内均值；产物无任何有效像素的空图判完全不匹配、按满值计入、照常显示">跳过</span>';
    return '<div style="display:flex;justify-content:space-between;align-items:center;gap:14px;padding:7px 2px;border-bottom:1px solid var(--border);font-size:12.5px">' +
      '<span>' + escHtml(nm) +
        '<span style="color:#667;font-size:11px;margin-left:7px">' + escHtml((m ? m.file : ks.kind + ".png") + " · " + grid) + "</span></span>" +
      '<span style="white-space:nowrap">' + score + "</span></div>";
  }).join("");
  const ov = document.createElement("div");
  ov.id = "kindScoreModal";
  ov.className = "modal-ov";
  const itDiff = (typeof it.diffPercent === "number" && it.diffPercent >= 0) ? it.diffPercent.toFixed(2) + "%" : "—";
  ov.innerHTML =
    '<div class="xcard" style="max-width:580px;width:100%;text-align:left;position:relative">' +
      // 与「匹配明细」同规：覆盖型弹窗看不到身后页面，标题先点明是哪个分类，再用一行 chip 摆出本帧关键数字
      '<div class="xt2">对照图分值 - ' + escHtml(it.state || "—") + '</div>' +
      '<div class="xmeta">' +
        '<span>本帧差异度<b>' + itDiff + '</b></span>' +
        '<span>对照图来源<b>' + escHtml(it.matchedFile || "—") + '</b></span>' +
      '</div>' +
      '<div style="color:var(--muted);font-size:11.5px;line-height:2;margin:2px 0 10px">' +
        "差异分值计算：该图的非透明区域与当前画面逐点比对的不匹配点占比<br>" +
        "色差按维度类别分两套：交集/多数类（交集六档 100/90/80/70/60/50（100% = 样本像素完全一致）、多数/多数块图、注意区与点击区交集图及各自 -unique）逐像素完全一致（三通道差都为 0）；均值类（均值/去重均值/均值块图/去重均值块图及各自 -unique）走逐通道容差（三通道差都不超过 execute.rgb-dist-threshold（默认 255/3=85）才一致；去重均值 = 先把样本该点出现过的颜色去重再平均，防重复采样把平均拉偏）。注意区交集图是各分类以关注点（未设 = 屏幕中心）为心的 1/8、1/32 方框 × 各交集档的交集图（每个分类都有）；点击区交集图是鼠标点击分类以点击点为心的同规格交集图。分类差异度 = 五族加权 (50A+15B+10C+10D+15E)/W：A 全图交集 12 张权 50、B 多数 6 张权 15、C 均值 6 张权 10、D 去重均值 6 张权 10、E 方框交集区（注意区 12 + 点击区 12）24 张权 15；每族先把族内各图不匹配点占比等权平均，再除以 W（参与分类产物齐全、恒为 100）；产物无任何有效像素的空图判完全不匹配、按满值计入、照常参与族均值</div>" +
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
  const at0 = execShownAt;
  btn.disabled = true;
  btn.textContent = "存入中…";
  const j = await execGet("/api/execute/mark", {
    method:"POST", headers:{ "Content-Type":"application/json" },
    body: JSON.stringify({ state: state })
  });
  if(j && j.ok){
    if(execShownAt === at0) execShownStored = true;   // 同帧存入成功：其它候选行与「存入待标注」联动置灰
    toast("已把当前画面存入分类「" + state + "」的样本，后台将自动刷新该分类的对照图（约 3 秒后开始重算，完成后后续识别即按新样本匹配）。", "ok");
  } else if(j && j.kind === "dup"){
    // 与某张已保存图差异 ≤ 手动阈值被拦截（非系统错误）：用琥珀「跳过」样式提示，文案与重复参考由后端给出
    toast(j.message || "当前画面与某张已保存图几乎重复（不一致像素占比 ≤ 手动阈值），未存入分类（如需改标请在标注模式修改该样本的分类）", "skip");
  } else {
    toast("标记失败：" + ((j && j.message) || "接口不可用"), "err");
  }
  if(execShownAt === at0){                           // 仍是同一帧：重建候选行并刷新「存入待标注」，不等下轮轮询
    renderExecCandidates((execLatest && execLatest.candidates) || []);
    renderExecSaveCap(execLatest);
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
  if(appMode !== "exec") return;    // 标注模式下 execPane 隐藏、画面区无尺寸，重排会把旧帧误缩成小图
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
/* 循环期间禁用手动「立即识别 / 执行动作」，停止后按最新结果恢复；
   「存入待标注」不在此锁定（自动识别中也可把当前画面另存为待标注） */
function execAutoSetManual(locked){
  const r = $("execRefresh"); if(r) r.disabled = locked;
  renderExecActBtn(execLatest || { recognized:false }, !locked && execIsClickable());
  renderExecSaveCap(execLatest);
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
vkPrefetch();                     // 顶栏「特征验证(N)」计数启动即取，不依赖先进验证视图
optPrefetch();                    // 顶栏「算法调优(N)」计数同理（算法个数，未验证时后端也下发 2 个骨架）
loadKindMeta();                   // 取 kind 元数据并建好全部对照图卡片（进汇总分析前就绪）
setInterval(pollTick, POLL_MS);
checkAppVersion();                    // 立即取一次基线
setInterval(checkAppVersion, META_MS);
setInterval(dedupProgTick, 1000);     // 启动重复清理进行中：「已耗时」逐秒走动（无进行态时直接返回）