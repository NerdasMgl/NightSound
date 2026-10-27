/* 夜莺界面：新手引导、今晚、报告（看板）、我的。数据全部来自 Java 端的 window.YH。 */
(function () {
  'use strict';
  var U = window.YHUI, h = U.h, icon = U.icon;
  var app = document.getElementById('app');

  // ================================================================= 与 Java 通信
  function Y(name) {
    var a = [];
    for (var i = 1; i < arguments.length; i++) a.push(String(arguments[i]));
    try { var b = window.YH; if (!b || !b[name]) return null; return b[name].apply(b, a); } catch (e) { return null; }
  }
  function YJ(name, def) {
    var args = Array.prototype.slice.call(arguments, 2);
    var r = Y.apply(null, [name].concat(args));
    if (r == null) return def;
    try { return JSON.parse(r); } catch (e) { return def; }
  }

  // ================================================================= 状态
  var S = {
    env: {}, st: {}, tab: 'tonight', rid: 0, fresh: false, nights: [], report: null,
    pickBin: null, pickApnea: null, pickMark: null, playing: 0, onb: -1, att: false, acc: {}, afterPerm: null, seenFinished: -1, micTimer: null, micOk: false, home: {}
  };

  var BRANDS = [
    ['小米 / 红米', '设置 > 应用 > 夜莺 > 省电策略 > 无限制；再打开“自启动”。'],
    ['华为 / 荣耀', '设置 > 应用 > 应用启动管理 > 夜莺 > 手动管理，三项都打开。'],
    ['OPPO / 一加 / realme', '设置 > 电池 > 夜莺 > 允许后台运行、允许自启动。'],
    ['vivo / iQOO', '设置 > 电池 > 后台高耗电 > 夜莺 > 允许。'],
    ['三星', '设置 > 电池 > 后台使用限制 > 不受限制的应用 > 加入夜莺。']
  ];

  // ================================================================= 小工具
  function btn(text, cls, onclick, extra) { var a = { 'class': 'btn ' + cls, onclick: onclick }; if (extra) for (var k in extra) a[k] = extra[k]; return h('button', a, text); }
  function card(kids, cls) { return h('div', { 'class': 'card' + (cls ? ' ' + cls : '') }, kids); }
  function sectionTitle(text, right) { return h('div', { 'class': 'section-title' }, [h('h2', { text: text }), right || null]); }
  function chip(text, tone) { return h('span', { 'class': 'chip' + (tone ? ' ' + tone : '') }, text); }
  function toneChip(text, tone) {
    if (!text) return null;
    return h('span', { 'class': 'chip ' + (tone === 'alert' ? 'alert' : tone === 'watch' ? 'watch' : tone === 'ok' ? 'ok' : '') }, [
      tone === 'alert' ? icon('warn', 14) : null, text]);
  }
  function setTheme() {
    var run = (S.st.running || S.st.waiting) && S.tab === 'tonight' && S.onb < 0;
    var base = S.env.dark === false ? 'day' : 'night';
    var t = run ? 'bedside' : base;
    document.documentElement.setAttribute('data-theme', t);
    Y('setTheme', t);
  }
  function saveScroll() { return window.pageYOffset || document.documentElement.scrollTop || 0; }

  // ================================================================= 渲染入口
  function render(keep) {
    var y = keep ? saveScroll() : 0;
    S.env = YJ('env', S.env || {});
    S.st = YJ('state', S.st || {});
    if (S.onb < 0 && !S.env.introDone && !S.skipIntro) S.onb = 0;
    setTheme();
    app.textContent = '';
    if (S.onb >= 0) { app.appendChild(viewOnboarding()); window.scrollTo(0, 0); return; }
    var running = S.st.running || S.st.waiting;
    if (running && S.tab === 'tonight') { app.appendChild(viewBedside()); window.scrollTo(0, 0); return; }
    var page = S.tab === 'tonight' ? viewTonight() : S.tab === 'report' ? viewReport() : viewMe();
    app.appendChild(page);
    app.appendChild(navBar());
    window.scrollTo(0, y);
  }

  function navBar() {
    function item(key, label, ic) {
      return h('button', { 'aria-current': S.tab === key ? 'page' : null, onclick: function () { go(key); } }, [
        h('span', { 'class': 'pill' }, icon(ic, 24)), label]);
    }
    return h('nav', { 'class': 'nav', 'aria-label': '主导航' }, [item('tonight', '今晚', 'moon'), item('report', '睡眠报告', 'chart'), item('me', '我的', 'person')]);
  }
  function go(tab, rid) {
    S.tab = tab; S.pickBin = S.pickApnea = S.pickMark = null; S.att = false;
    if (tab === 'report') { S.rid = rid || 0; if (!rid) S.fresh = false; S.dirty = true; }
    stopClip();
    render(false);
  }

  // ================================================================= 新手引导
  function viewOnboarding() {
    var n = S.onb, total = 5, env = S.env;
    var inner = h('div', { 'class': 'onb-inner fade' });
    inner.appendChild(h('div', { 'class': 'dots', 'aria-label': '第 ' + (n + 1) + ' 步，共 ' + total + ' 步' }, [0, 1, 2, 3, 4].map(function (i) { return h('i', { 'class': i === n ? 'on' : '' }); })));
    var foot = h('div', { 'class': 'foot' });
    function next() { stopMic(); S.onb = n + 1; if (S.onb >= total) finishOnb(); else render(); }
    function back() { stopMic(); S.onb = Math.max(0, n - 1); render(); }
    function skipAll() { stopMic(); finishOnb(); }

    if (n === 0) {
      inner.appendChild(h('div', { 'class': 'art' }, heroArt()));
      inner.appendChild(h('div', { 'class': 'sub label-lg', style: 'text-align:center;letter-spacing:4px', text: '夜莺' }));
      inner.appendChild(h('h1', { 'class': 'headline-md', style: 'text-align:center', text: '让手机陪你睡一晚' }));
      inner.appendChild(h('p', { 'class': 'body-lg muted', style: 'text-align:center;margin:0 0 8px', text: '睡前点一下，手机放在枕边，整晚安静地听。早上看一份能看懂的报告。' }));
      [['moon', '睡了多久，睡得稳不稳', '不用戴手环，根据手机的动静和声音来估算。'],
       ['wave', '打鼾，和疑似呼吸暂停', '打鼾多不多、响不响，有没有突然停了一会儿，都标在一张时间线上。'],
       ['mic', '夜里的其他声音', '咳嗽、说话、磨牙。每一项都能回放一小段录音来核对。'],
       ['lock', '录音不出手机', '声音在手机里分析，夜莺根本没有联网的权限。']].forEach(function (f) {
        inner.appendChild(h('div', { 'class': 'feat' }, [h('div', { 'class': 'ic' }, icon(f[0], 22)),
          h('div', null, [h('div', { 'class': 'label-lg', text: f[1] }), h('div', { 'class': 'body-md muted', text: f[2] })])]));
      });
      foot.appendChild(btn('开始设置（约 1 分钟）', 'primary block', next));
      foot.appendChild(btn('我会用了，跳过', 'text block', skipAll));
    } else if (n === 1) {
      inner.appendChild(h('h1', { 'class': 'headline-md', text: '把手机放在枕边' }));
      inner.appendChild(h('div', { 'class': 'art' }, placementArt()));
      [['离头 0.5 到 1 米', '太远听不清鼾声，太近会录到呼吸的杂音。屏幕朝下。'],
       ['接上充电线', '整晚录音会用掉不少电，睡前插上最省心。'],
       ['别被盖住', '不要塞进被子、枕头底下或衣服口袋。'],
       ['放在床上最好', '手机能感觉到你翻身，判断什么时候睡着、什么时候醒来会更准。放床头柜也行。']].forEach(function (f, i) {
        inner.appendChild(h('div', { 'class': 'feat' }, [h('div', { 'class': 'ic data-md', text: String(i + 1) }),
          h('div', null, [h('div', { 'class': 'label-lg', text: f[0] }), h('div', { 'class': 'body-md muted', text: f[1] })])]));
      });
      foot.appendChild(btn('知道了', 'primary block', next));
      foot.appendChild(btn('上一步', 'text block', back));
    } else if (n === 2) {
      inner.appendChild(h('h1', { 'class': 'headline-md', text: '需要两个许可' }));
      inner.appendChild(h('p', { 'class': 'body-md muted', style: 'margin:0 0 12px', text: '点下面的按钮，系统会弹出询问，选“允许”。' }));
      inner.appendChild(permRow('mic', '麦克风', '必须', '用来听鼾声和呼吸。声音只在手机里处理，不会上传。', env.hasMic));
      inner.appendChild(permRow('bell', '通知', '建议', '夜里在通知栏显示“正在监测”，系统看到有这个提示，就不容易把夜莺关掉。', env.hasNotif));
      if (!env.hasMic && S.permAsked) {
        inner.appendChild(h('div', { 'class': 'banner info', style: 'margin-top:12px' }, [h('div', { 'class': 'body-md' }, [
          '如果系统没有再弹窗，请到“系统设置 > 应用 > 夜莺 > 权限”里手动打开麦克风。'])]));
        foot.appendChild(btn('打开系统设置', 'tonal block', function () { Y('openAppSettings'); }));
      }
      if (env.hasMic) foot.appendChild(btn('继续', 'primary block', next));
      else foot.appendChild(btn('允许', 'primary block', function () { S.permAsked = true; S.afterPerm = null; Y('requestPerms'); }));
      foot.appendChild(btn('上一步', 'text block', back));
    } else if (n === 3) {
      inner.appendChild(h('h1', { 'class': 'headline-md', text: '让夜莺整晚不被关掉' }));
      inner.appendChild(h('p', { 'class': 'body-lg muted', style: 'margin:0 0 12px', text: '很多手机为了省电，锁屏后会把后台程序清理掉，早上就只剩半夜的记录。' }));
      var ok = env.ignoringBattery;
      inner.appendChild(h('div', { 'class': 'card flat' }, h('div', { 'class': 'row' }, [
        h('div', { 'class': 'status-ic ' + (ok ? 'ok' : 'todo') }, icon(ok ? 'check' : 'battery', 18)),
        h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: ok ? '已设为不受限制' : '后台运行' }),
          h('div', { 'class': 'caption muted', text: ok ? '很好，夜里不会被省电功能打断。' : '点“去设置”，在弹出的窗口里选“允许”。' })])])));
      inner.appendChild(accordion('brands', '我的手机是小米、华为、OPPO、vivo、三星……', function () {
        return h('div', null, BRANDS.map(function (b) { return h('div', { style: 'padding:8px 0' }, [h('div', { 'class': 'label-lg', text: b[0] }), h('div', { 'class': 'body-md muted', text: b[1] })]); })
          .concat([h('div', { 'class': 'caption muted', style: 'padding-top:4px', text: '其他机型可以搜索“dontkillmyapp”。' })]));
      }));
      if (!ok) foot.appendChild(btn('去设置', 'primary block', function () { Y('requestIgnoreBattery'); }));
      foot.appendChild(btn(ok ? '继续' : '稍后再说', ok ? 'primary block' : 'text block', next));
      foot.appendChild(btn('上一步', 'text block', back));
    } else {
      inner.appendChild(h('h1', { 'class': 'headline-md', text: '试一下麦克风' }));
      inner.appendChild(h('p', { 'class': 'body-lg muted', style: 'margin:0', text: '对着手机说句话，或者拍拍手。下面的条动起来，就说明夜莺听得到。' }));
      var meter = h('i'), status = h('div', { 'class': 'label-lg muted', text: env.hasMic ? '正在听……' : '还没有麦克风许可，请回到上一步允许。' });
      inner.appendChild(h('div', { 'class': 'meter', role: 'progressbar', 'aria-label': '麦克风音量' }, meter));
      inner.appendChild(status);
      var pass = h('div', { 'class': 'banner info', style: 'margin-top:16px;display:none' }, [h('div', { 'class': 'status-ic ok' }, icon('check', 18)),
        h('div', null, [h('div', { 'class': 'label-lg', text: '麦克风正常' }), h('div', { 'class': 'body-md muted', text: '睡前点“开始监测”就可以了。夜里屏幕会自动变暗。' })])]);
      inner.appendChild(pass);
      inner.appendChild(h('div', { 'class': 'banner info', style: 'margin-top:16px' }, [icon('info', 20), h('div', { 'class': 'body-md muted', text: '夜莺是日常记录工具，估算值不能替代医生的诊断。' })]));
      if (env.hasMic) startMic(meter, status, pass);
      foot.appendChild(btn('完成，去看看今晚', 'primary block', next));
      foot.appendChild(btn('上一步', 'text block', back));
    }
    inner.appendChild(foot);
    return h('div', { 'class': 'onb' }, inner);
  }

  function permRow(ic, name, tag, desc, granted) {
    return h('div', { 'class': 'card flat' }, h('div', { 'class': 'row top' }, [
      h('div', { 'class': 'status-ic ' + (granted ? 'ok' : 'todo') }, icon(granted ? 'check' : ic, 18)),
      h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg' }, [name + ' ', chip(granted ? '已允许' : tag, granted ? 'ok' : (tag === '必须' ? 'watch' : ''))]),
        h('div', { 'class': 'body-md muted', text: desc })])]));
  }

  function startMic(meter, status, pass) {
    stopMic();
    Y('micTest', 1);
    var good = 0;
    S.micTimer = setInterval(function () {
      var lv = parseFloat(Y('micLevel') || '0') || 0;
      meter.style.width = Math.min(100, Math.round(lv * 100)) + '%';
      if (lv > 0.12) good++;
      if (good >= 3 && !S.micOk) { S.micOk = true; status.textContent = '听到了。'; status.className = 'label-lg ok-text'; pass.style.display = 'flex'; }
    }, 100);
  }
  function stopMic() { if (S.micTimer) { clearInterval(S.micTimer); S.micTimer = null; Y('micTest', 0); } }
  function finishOnb() { Y('introDone'); S.onb = -1; S.skipIntro = true; S.tab = 'tonight'; S.coach = true; render(); }

  function heroArt() {
    var s = U.s;
    return s('svg', { viewBox: '0 0 160 120', width: 200, height: 150, 'aria-hidden': 'true' }, [
      s('circle', { cx: 80, cy: 62, r: 46, fill: 'var(--accent-container)' }),
      s('path', { d: 'M92 30a34 34 0 1 0 22 52 26 26 0 0 1-22-52z', fill: 'var(--accent)' }),
      s('circle', { cx: 28, cy: 28, r: 2.5, fill: 'var(--accent-text)' }), s('circle', { cx: 136, cy: 40, r: 2, fill: 'var(--accent-text)' }),
      s('circle', { cx: 120, cy: 16, r: 1.5, fill: 'var(--accent-text)' }), s('circle', { cx: 42, cy: 100, r: 1.8, fill: 'var(--accent-text)' }),
      s('path', { d: 'M30 108 q12 -8 24 0 t24 0 t24 0 t24 0', fill: 'none', stroke: 'var(--ok)', 'stroke-width': 2, 'stroke-linecap': 'round', opacity: '.8' })]);
  }
  function placementArt() {
    var s = U.s;
    return s('svg', { viewBox: '0 0 300 140', width: '100%', height: 150, role: 'img', 'aria-label': '手机放在枕边，离头约 0.5 到 1 米' }, [
      s('rect', { x: 14, y: 96, width: 272, height: 30, rx: 8, fill: 'var(--surface-3)' }),
      s('rect', { x: 22, y: 78, width: 76, height: 22, rx: 10, fill: 'var(--surface-2)', stroke: 'var(--outline)' }),
      s('circle', { cx: 56, cy: 68, r: 15, fill: 'var(--ink-muted)' }),
      s('rect', { x: 92, y: 84, width: 186, height: 18, rx: 8, fill: 'var(--accent-container)' }),
      s('rect', { x: 128, y: 88, width: 34, height: 8, rx: 3, fill: 'var(--accent)' }),
      s('line', { x1: 74, x2: 128, y1: 52, y2: 52, stroke: 'var(--ink-muted)', 'stroke-width': 1.5 }),
      s('line', { x1: 74, x2: 74, y1: 46, y2: 58, stroke: 'var(--ink-muted)', 'stroke-width': 1.5 }),
      s('line', { x1: 128, x2: 128, y1: 46, y2: 58, stroke: 'var(--ink-muted)', 'stroke-width': 1.5 }),
      s('text', { x: 101, y: 42, 'text-anchor': 'middle', fill: 'var(--ink)', 'font-size': 12, text: '0.5 到 1 米' }),
      s('text', { x: 145, y: 118, 'text-anchor': 'middle', fill: 'var(--ink-muted)', 'font-size': 11, text: '手机，屏幕朝下' }),
      s('path', { d: 'M232 70 l10 -10 l10 10', fill: 'none', stroke: 'var(--ink-muted)', 'stroke-width': 1.5, opacity: '0' })]);
  }

  function accordion(key, title, body) {
    var open = !!S.acc[key];
    var box = h('div', { 'class': 'acc card flat' + (open ? ' open' : '') });
    var content = null;
    var head = h('button', { 'aria-expanded': open ? 'true' : 'false', onclick: function () {
      S.acc[key] = !S.acc[key];
      var o = S.acc[key];
      box.className = 'acc card flat' + (o ? ' open' : '');
      head.setAttribute('aria-expanded', o ? 'true' : 'false');
      if (o) { content = h('div', null, body()); box.appendChild(content); } else if (content) { box.removeChild(content); content = null; }
    } }, [h('span', { text: title }), h('span', { 'class': 'chev' }, icon('down', 24))]);
    box.appendChild(head);
    if (open) { content = h('div', null, body()); box.appendChild(content); }
    return box;
  }

  // ================================================================= 今晚
  function greeting() {
    var hr = new Date().getHours();
    return hr < 5 ? '夜深了' : hr < 11 ? '早上好' : hr < 14 ? '中午好' : hr < 18 ? '下午好' : '晚上好';
  }

  function viewTonight() {
    var env = S.env, home = YJ('home', {});
    S.home = home;
    var page = h('div', { 'class': 'page fade' });
    page.appendChild(h('div', { 'class': 'topbar' }, [h('div', { 'class': 'brand', text: '夜莺' }), h('div', { 'class': 'sub', text: greeting() + ' · ' + U.dayWeek(Date.now()) })]));

    if (home.interrupted) {
      var it = home.interrupted;
      page.appendChild(h('div', { 'class': 'banner alert' }, [icon('warn', 24), h('div', null, [h('h3', { text: '上一次监测被中断了' }),
        h('p', { 'class': 'body-md', text: it.msg }),
        h('div', null, [btn('查看中断前的记录', 'text', function () { Y('dismissInterrupted'); go('report', it.night); }), btn('知道了', 'text', function () { Y('dismissInterrupted'); render(true); })])])]));
    }
    if (home.doctor) page.appendChild(doctorBanner(home.doctor));

    if (S.coach) {
      page.appendChild(h('div', { 'class': 'banner watch' }, [icon('info', 22), h('div', null, [h('h3', { text: '第一晚怎么用' }),
        h('p', { 'class': 'body-md', text: '睡前点“开始监测”，手机放枕边充电；早上回到这里，按住“结束监测”，就会出现报告。第一晚的数据少，多记录几晚才看得出规律。' }),
        btn('知道了', 'text', function () { S.coach = false; render(true); })])]));
    }

    // 开始
    var hero = h('div', { 'class': 'card hero-start' });
    hero.appendChild(h('div', { 'class': 'moon' }, icon('moon', 36)));
    hero.appendChild(h('h2', { 'class': 'headline-md', text: '准备睡觉了吗？' }));
    hero.appendChild(h('p', { 'class': 'body-md muted', style: 'margin:8px 0 16px', text: '点下面的按钮，把手机放在枕边。早上回到这里结束监测，就能看到报告。' }));
    hero.appendChild(btn('开始监测', 'primary block big', startFlow));
    hero.appendChild(h('div', { 'class': 'caption muted', style: 'margin-top:10px', text: '监测时屏幕会自动变暗，不会影响睡觉。' }));
    page.appendChild(hero);

    // 睡前检查
    var chk = card([h('h2', { text: '睡前检查' }), h('p', { 'class': 'hint', text: '三件事做好，整晚的记录才完整。' })]);
    chk.appendChild(checkRow('mic', '麦克风', env.hasMic, '已允许', '需要允许', env.hasMic ? null : btn('去允许', 'text', function () { S.afterPerm = null; Y('requestPerms'); })));
    chk.appendChild(checkRow('battery', '后台运行', env.ignoringBattery, '不受限制', '建议设置', env.ignoringBattery ? null : btn('去设置', 'text', function () { Y('requestIgnoreBattery'); }), true));
    chk.appendChild(checkRow('phone', '手机摆放', null, '', '枕边 0.5 到 1 米，屏幕朝下，接上充电线', btn('怎么放', 'text', placementSheet)));
    chk.appendChild(checkRow('bell', '通知', env.hasNotif, '已允许', '建议允许', env.hasNotif ? null : btn('去允许', 'text', function () { S.afterPerm = null; Y('requestPerms'); }), true));
    page.appendChild(chk);

    // 今晚情况
    var tagsBox = card([h('h2', { text: '今晚的情况（可选）' }), h('p', { 'class': 'hint', text: '点几个今天的习惯。攒几晚之后，报告会帮你看哪个和打鼾最有关系。' })]);
    var prefs = YJ('prefs', {}); var sel = {};
    (prefs.tags || []).forEach(function (t) { sel[t] = true; });
    var ALL = ['咖啡因', '酒精', '晚餐较晚', '运动', '鼻塞', '压力大', '仰卧', '侧卧'];
    var chips = h('div', { 'class': 'chips' });
    ALL.forEach(function (t) {
      var b = h('button', { 'class': 'chip-btn', 'aria-pressed': sel[t] ? 'true' : 'false', onclick: function () {
        sel[t] = !sel[t]; b.setAttribute('aria-pressed', sel[t] ? 'true' : 'false');
        Y('setTags', ALL.filter(function (x) { return sel[x]; }).join(','));
      } }, t);
      chips.appendChild(b);
    });
    tagsBox.appendChild(chips);
    page.appendChild(tagsBox);

    // 最近一晚
    if (home.latest) {
      var l = home.latest;
      var lc = h('button', { 'class': 'card', style: 'width:100%;text-align:left;display:block', onclick: function () { go('report', l.id); } }, [
        h('div', { 'class': 'row', style: 'min-height:0' }, [h('div', { 'class': 'grow' }, [
          h('div', { 'class': 'label-md muted', text: '最近一晚 · ' + l.dateLabel }),
          h('div', { 'class': 'title', style: 'margin-top:4px', text: l.tooShort ? '记录较短' : (l.verdict || verdictOf(l.score)) }),
          h('div', { 'class': 'body-md muted', text: (l.sleepMin > 0 ? '睡了 ' + l.sleepLabel : '没有判断出入睡') + ' · 打鼾' + l.snoreWord + (l.apnea > 0 ? ' · 疑似呼吸暂停 ' + l.apnea + ' 次' : '') })]),
          h('div', { 'class': 'score accent-text', style: 'font-size:40px;line-height:44px', text: l.score >= 0 ? String(l.score) : '--' }), icon('right', 24)])]);
      page.appendChild(lc);
    }

    // 白天困倦
    page.appendChild(sleepyCard('此刻困不困？', '白天犯困是你最在意的事。每天记一两次，报告里会把它和夜里的鼾声放在一起看。'));

    page.appendChild(h('div', { 'class': 'footnote', text: '夜莺是日常记录工具，估算值不能替代医生的诊断。' }));
    return page;
  }

  function verdictOf(score) {
    if (score < 0) return '记录太短';
    return score >= 85 ? '睡得安稳' : score >= 70 ? '整体不错' : score >= 50 ? '睡得一般' : '睡得不太好';
  }

  function checkRow(ic, name, ok, okText, todoText, action, soft) {
    var kind = ok === null ? 'todo' : ok ? 'ok' : (soft ? 'todo' : 'bad');
    return h('div', { 'class': 'row' }, [
      h('div', { 'class': 'status-ic ' + kind }, icon(ok ? 'check' : ic, 18)),
      h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: name }), h('div', { 'class': 'caption ' + (kind === 'bad' ? 'alert-text' : 'muted'), text: ok ? okText : todoText })]),
      action]);
  }

  function doctorBanner(d) {
    return h('div', { 'class': 'banner alert' }, [icon('warn', 24), h('div', null, [h('h3', { text: '建议去做一次专业检查' }),
      h('p', { 'class': 'body-md', text: d.text }), h('p', { 'class': 'caption muted', text: '触发原因：' + d.reason + '。' }), h('p', { 'class': 'label-lg', text: d.drive })])]);
  }

  function sleepyCard(title, sub) {
    var c = card([h('h2', { text: title }), h('p', { 'class': 'hint', text: sub })]);
    var labels = ['很清醒', '清醒', '一般', '有点困', '很困'];
    var done = null;
    var scale = h('div', { 'class': 'scale', role: 'group', 'aria-label': title });
    labels.forEach(function (t, i) {
      scale.appendChild(h('button', { 'aria-pressed': 'false', onclick: function () {
        Y('putCheckIn', i + 1);
        c.removeChild(scale);
        c.appendChild(h('div', { 'class': 'row ok-text' }, [icon('check', 20), h('div', { 'class': 'body-md', text: '已记录：' + t })]));
      } }, t));
    });
    c.appendChild(scale);
    return c;
  }

  function placementSheet() {
    U.openSheet(function (sheet, close) {
      sheet.appendChild(h('h2', { 'class': 'headline-md', style: 'margin-bottom:8px', text: '手机怎么放' }));
      sheet.appendChild(h('div', { 'class': 'art' }, placementArt()));
      [['离头 0.5 到 1 米，屏幕朝下', ''], ['接上充电线', '整晚录音比较耗电。'], ['别被盖住', '不要塞进被子、枕头底下或衣服口袋。'], ['放床上最好', '手机能感觉到翻身，判断入睡和醒来更准；放床头柜也行。']].forEach(function (f, i) {
        sheet.appendChild(h('div', { 'class': 'feat' }, [h('div', { 'class': 'ic data-md', text: String(i + 1) }), h('div', null, [h('div', { 'class': 'label-lg', text: f[0] }), f[1] ? h('div', { 'class': 'body-md muted', text: f[1] }) : null])]));
      });
      sheet.appendChild(btn('知道了', 'primary block', close));
    });
  }

  // ---------- 开始 / 结束 ----------
  function startFlow() {
    var env = YJ('env', S.env);
    S.env = env;
    if (!env.hasMic) { S.afterPerm = startFlow; Y('requestPerms'); return; }
    if (!env.batteryHintDone && !env.ignoringBattery) {
      Y('batteryHintDone');
      U.confirmBox('先设置一下后台运行', '不设置的话，很多手机会在锁屏后把夜莺关掉，早上就只剩半夜的记录。要现在设置吗？', '去设置',
        function () { Y('requestIgnoreBattery'); }, '直接开始', false,
        function () { Y('start'); setTimeout(function () { render(); }, 200); });
      return;
    }
    Y('start');
    setTimeout(function () { render(); }, 200);
  }

  // ================================================================= 床头屏
  var bed = {};
  function viewBedside() {
    bed.clock = h('div', { 'class': 'clock', text: '--:--' });
    bed.status = h('div', { 'class': 'body-lg muted', text: '正在启动……' });
    bed.snore = h('b', { text: '0' }); bed.apnea = h('b', { text: '0' });
    bed.level = h('i');
    bed.warn = h('div', { 'class': 'label-lg alert-text', style: 'min-height:44px;margin-top:20px;max-width:320px' });
    bed.hint = h('div', { 'class': 'caption muted', style: 'max-width:300px;margin-top:8px', text: '对着手机说句话，上面的条会动，说明麦克风正常。屏幕会自动熄灭，监测继续。' });
    var prog = h('i'), lab = h('span', { text: '按住 1.5 秒结束监测' });
    var hold = h('button', { 'class': 'holdbtn', 'aria-label': '按住 1.5 秒结束监测' }, [prog, lab]);
    var t0 = 0, raf = 0;
    function frame() {
      var f = Math.min(1, (Date.now() - t0) / 1500);
      prog.style.width = (f * 100) + '%';
      if (f >= 1) { raf = 0; lab.textContent = '正在保存……'; Y('stop'); return; }
      raf = requestAnimationFrame(frame);
    }
    function down(e) { e.preventDefault(); t0 = Date.now(); lab.textContent = '继续按住……'; raf = requestAnimationFrame(frame); }
    function up() { if (raf) { cancelAnimationFrame(raf); raf = 0; prog.style.width = '0'; lab.textContent = '按住 1.5 秒结束监测'; } }
    hold.addEventListener('touchstart', down, { passive: false }); hold.addEventListener('mousedown', down);
    ['touchend', 'touchcancel', 'mouseup', 'mouseleave'].forEach(function (ev) { hold.addEventListener(ev, up); });
    hold.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    var el = h('div', { 'class': 'bedside fade' }, [
      bed.clock, bed.status,
      h('div', { 'class': 'stats' }, [h('div', null, [bed.snore, h('span', { 'class': 'caption muted', text: '鼾声（次）' })]), h('div', null, [bed.apnea, h('span', { 'class': 'caption muted', text: '疑似呼吸暂停（次）' })])]),
      h('div', { 'class': 'level', role: 'progressbar', 'aria-label': '此刻声音大小' }, bed.level),
      h('div', { 'class': 'caption muted', style: 'margin-top:6px', text: '此刻的声音' }),
      bed.warn, bed.hint, h('div', { style: 'flex:1;min-height:32px' }), hold]);
    updateBedside();
    return el;
  }
  function updateBedside() {
    if (!bed.clock || !bed.clock.isConnected) return;
    var st = S.st, now = Date.now();
    bed.clock.textContent = U.clock(now);
    if (!st.running) { bed.status.textContent = st.waiting ? '正在启动……' : '正在保存……'; return; }
    var mins = Math.floor((now - st.startMs) / 60000);
    bed.status.textContent = '监测中 · 已 ' + Math.floor(mins / 60) + ' 小时 ' + (mins % 60) + ' 分';
    bed.snore.textContent = st.snore; bed.apnea.textContent = st.apnea;
    bed.level.style.width = Math.max(0, Math.min(100, (st.level - st.floor) / 40 * 100)) + '%';
    var w = '';
    if (st.error) w = st.error;
    else if (st.micSilent) w = '麦克风一分钟内没有任何信号，可能被其他应用占用，或系统限制了录音。';
    else if (mins >= 3 && !st.accelOk) w = '没有收到体动数据：入睡与醒来的判断会不准。';
    bed.warn.textContent = w;
    if (mins >= 5) bed.hint.textContent = '屏幕会自动熄灭，监测继续。手机放枕边、接上充电线。';
  }

  // ================================================================= 报告
  function loadReport() {
    if (S.dirty || !S.nights.length) { S.nights = YJ('nights', []); S.cache = {}; S.dirty = false; }
    var id = S.rid;
    var ok = false;
    S.nights.forEach(function (n) { if (n.id === id) ok = true; });
    if (!ok) id = S.nights[0] ? S.nights[0].id : 0;
    S.rid = id;
    if (!id) { S.report = null; return; }
    if (!S.cache) S.cache = {};
    if (!S.cache[id]) S.cache[id] = YJ('report', null, id);
    S.report = S.cache[id];
  }

  function viewReport() {
    loadReport();
    var page = h('div', { 'class': 'page fade' });
    var r = S.report;
    if (!r) {
      page.appendChild(h('div', { 'class': 'topbar' }, h('div', { 'class': 'headline-md', text: '睡眠报告' })));
      page.appendChild(h('div', { 'class': 'empty' }, [h('div', { 'class': 'art' }, heroArt()), h('h2', { 'class': 'title', text: '还没有记录' }),
        h('p', { 'class': 'body-md muted', text: '今晚睡前点“开始监测”，明天早上这里就会有一份报告。' }), btn('去开始监测', 'primary', function () { go('tonight'); })]));
      return page;
    }
    var idx = -1;
    S.nights.forEach(function (n, i) { if (n.id === r.id) idx = i; });
    var older = S.nights[idx + 1], newer = idx > 0 ? S.nights[idx - 1] : null;

    page.appendChild(h('div', { 'class': 'datebar' }, [
      h('button', { 'class': 'icon-btn', 'aria-label': '前一晚', disabled: older ? null : 'true', onclick: function () { if (older) go('report', older.id); } }, icon('left', 24)),
      h('div', { 'class': 'mid' }, [h('div', { 'class': 'title', text: r.dateLabel }), h('div', { 'class': 'caption muted', text: r.bedLabel + ' 上床 · ' + r.endLabel + ' 结束' })]),
      h('button', { 'class': 'icon-btn', 'aria-label': '后一晚', disabled: newer ? null : 'true', onclick: function () { if (newer) go('report', newer.id); } }, icon('right', 24)),
      h('button', { 'class': 'btn text', onclick: nightsSheet, text: '全部' })]));

    if (S.fresh) page.appendChild(h('div', { 'class': 'banner info' }, [icon('check', 22), h('div', null, [h('h3', { text: '监测已保存' }), h('p', { 'class': 'body-md muted', text: '下面是这一晚的报告。' })])]));
    if (r.doctor) page.appendChild(doctorBanner(r.doctor));
    (r.notes || []).forEach(function (t) { page.appendChild(h('div', { 'class': 'banner info' }, [icon('info', 20), h('div', { 'class': 'body-md muted', text: t })])); });

    // 概览
    page.appendChild(card(h('div', { 'class': 'hero' }, [U.scoreRing(r.score, 112), h('div', { style: 'min-width:0' }, [
      h('div', { 'class': 'headline-md', text: r.verdict }), h('p', { 'class': 'body-md', style: 'margin:6px 0 4px', text: r.headline }), h('div', { 'class': 'caption muted', text: r.scoreNote })])])));

    // 四个指标
    var g = h('div', { 'class': 'grid2' });
    ['sleep', 'snore', 'apnea', 'calm'].forEach(function (k) { g.appendChild(tile(r.tiles[k])); });
    page.appendChild(g);
    if (r.tags && r.tags.length) page.appendChild(h('div', { 'class': 'caption muted', style: 'margin:-4px 4px 12px', text: '这晚的习惯：' + r.tags.join('、') }));

    // 时间线
    page.appendChild(sectionTitle('夜里发生了什么'));
    page.appendChild(timelineCard(r));

    // 注意事项
    if (r.attention && r.attention.length) {
      page.appendChild(sectionTitle('最该注意的几件事'));
      var shown = S.att ? r.attention : r.attention.slice(0, 3);
      shown.forEach(function (a) {
        page.appendChild(card(h('div', { 'class': 'att s' + a.sev }, [h('div', { 'class': 'bar' }), h('div', null, [
          h('h3', { text: a.title }), h('p', { 'class': 'body-md', text: a.body }), h('p', { 'class': 'body-md do', text: a.action })])])));
      });
      if (r.attention.length > 3 && !S.att) page.appendChild(btn('再看 ' + (r.attention.length - 3) + ' 条提示', 'tonal block', function () { S.att = true; render(true); }));
    }

    // 和平时比
    if (r.compare && r.compare.length) {
      page.appendChild(sectionTitle('和你平时比'));
      var cc = card();
      r.compare.forEach(function (c) {
        cc.appendChild(h('div', { 'class': 'cmp' }, [h('div', { 'class': 'arrow', 'aria-hidden': 'true', text: c.dir > 0 ? '↑' : c.dir < 0 ? '↓' : '＝' }),
          h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: c.label + '：' + c.now }), h('div', { 'class': 'body-md muted', text: c.text })])]));
      });
      page.appendChild(cc);
    }

    // 录音
    if (r.clips && r.clips.length) {
      page.appendChild(sectionTitle('听一听'));
      var rc = card(h('p', { 'class': 'hint', text: '回放夜里的片段，自己核对一下判断得对不对。' }));
      r.clips.forEach(function (c) { rc.appendChild(clipRow(c)); });
      page.appendChild(rc);
    }

    // 最近几晚
    if (S.nights.length >= 2) {
      page.appendChild(sectionTitle('最近几晚'));
      page.appendChild(trendCard(r.id));
    }

    // 白天困倦（刚结束监测时）
    if (S.fresh) page.appendChild(sleepyCard('现在困不困？', '早上起来的感觉也记一下，和夜里的数据放在一起才看得出关系。'));

    // 详细数据
    page.appendChild(h('div', { style: 'height:12px' }));
    page.appendChild(accordion('detail', '详细数据和说明', function () { return detailBody(r); }));
    page.appendChild(h('div', { 'class': 'footnote', text: '夜莺是日常记录工具，估算值不能替代医生的诊断。' }));
    return page;
  }

  // “7 小时 6 分”：数字用等宽字体，单位用小一点的无衬线字，整体更紧凑
  function valueNodes(str) {
    var parts = String(str).split(/(\d+(?:\.\d+)?%?)/), out = [];
    parts.forEach(function (p) {
      if (!p) return;
      if (/^\d/.test(p)) out.push(h('span', { 'class': 'num', text: p }));
      else out.push(h('span', { 'class': 'u', text: p.replace(/^\s+|\s+$/g, '') }));
    });
    return out;
  }

  function tile(t) {
    return h('div', { 'class': 'tile ' + (t.tone || '') }, [
      h('div', { 'class': 'top' }, [h('div', { 'class': 'label-md muted', text: t.title }), toneChip(t.chip, t.tone)]),
      h('div', { 'class': 'val' }, valueNodes(t.value)), t.unit ? h('div', { 'class': 'unit', text: t.unit }) : null,
      h('div', { 'class': 'caption muted', text: t.sub })]);
  }

  function timelineCard(r) {
    var tl = r.timeline;
    var c = card();
    c.appendChild(h('p', { 'class': 'body-lg', style: 'margin:0 0 4px', text: tl.summary }));
    var chart = h('div', { 'class': 'chart-wrap' });
    var pick = h('div');
    function draw() {
      chart.textContent = '';
      chart.appendChild(U.timeline(tl, {
        selectedBin: S.pickBin ? S.pickBin.i : null, selectedApnea: S.pickApnea ? S.pickApnea.id : null,
        onBin: function (b) { S.pickBin = b; S.pickApnea = null; S.pickMark = null; draw(); drawPick(); },
        onApnea: function (a) { S.pickApnea = a; S.pickBin = null; S.pickMark = null; draw(); drawPick(); },
        onMark: function (m) { S.pickMark = m; S.pickBin = null; S.pickApnea = null; draw(); drawPick(); }
      }));
    }
    function drawPick() {
      pick.textContent = '';
      var el = null;
      if (S.pickBin) {
        var b = S.pickBin, a = tl.t0 + b.i * tl.binMs, mins = Math.round(b.share * 10);
        el = h('div', { 'class': 'pick' }, [h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: U.clock(a) + ' 到 ' + U.clock(a + tl.binMs) }),
          h('div', { 'class': 'body-md muted', text: '这 10 分钟里约有 ' + Math.max(1, mins) + ' 分钟在打鼾，' + (b.lv === 3 ? '声音较响' : b.lv === 2 ? '声音中等' : '声音较轻') + '。' })])]);
      } else if (S.pickApnea) {
        var p = S.pickApnea;
        var clip = findClip(r, p.id);
        el = h('div', { 'class': 'pick' }, [h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: p.label + ' · 疑似呼吸暂停' }),
          h('div', { 'class': 'body-md muted', text: '鼾声中断了约 ' + p.sec + ' 秒才重新响起（估算）。' + (p.clip ? '' : '这一段没有保存录音。') })]),
          p.clip ? playBtn(p.id) : null]);
      } else if (S.pickMark) {
        var m = S.pickMark, nm = { cough: '咳嗽', talk: '可能的说话声', grind: '疑似磨牙（实验功能）' }[m.k] || '其他声音';
        el = h('div', { 'class': 'pick' }, [h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: m.label + ' · ' + nm }),
          h('div', { 'class': 'body-md muted', text: m.clip ? '点右边可以听一下。' : '这一段没有保存录音。' })]), m.clip ? playBtn(m.id) : null]);
      }
      if (el) pick.appendChild(el);
    }
    draw(); drawPick();
    c.appendChild(chart);

    // 图例：只列出这晚实际出现的东西
    var lg = h('div', { 'class': 'legend' });
    function lgi(color, text, shape) { return h('span', null, [h('i', { style: 'background:' + color + (shape === 'round' ? ';border-radius:50%' : '') }), text]); }
    if (tl.snore.length) { lg.appendChild(lgi('var(--loud-1)', '鼾声轻')); lg.appendChild(lgi('var(--loud-2)', '中')); lg.appendChild(lgi('var(--loud-3)', '响')); }
    if (tl.motionOk) { lg.appendChild(lgi('var(--stage-awake)', '醒着')); lg.appendChild(lgi('var(--stage-light)', '浅睡')); lg.appendChild(lgi('var(--stage-deep)', '深睡（估算）')); }
    if (tl.apnea.length) lg.appendChild(lgi('var(--event-apnea)', '疑似呼吸暂停'));
    var kinds = {}; tl.marks.forEach(function (m) { kinds[m.k] = 1; });
    if (kinds.cough) lg.appendChild(lgi('var(--event-other)', '咳嗽', 'round'));
    if (kinds.talk) lg.appendChild(lgi('var(--event-talk)', '说话'));
    if (kinds.grind) lg.appendChild(lgi('var(--event-grind)', '磨牙（实验）'));
    c.appendChild(lg);
    c.appendChild(pick);

    // 暂停列表
    if (tl.apnea.length) {
      var lst = h('div', { 'class': 'chips', style: 'margin-top:12px' });
      tl.apnea.slice(0, 12).forEach(function (a, i) {
        lst.appendChild(h('button', { 'class': 'chip-btn', 'aria-pressed': S.pickApnea && S.pickApnea.id === a.id ? 'true' : 'false', onclick: function () {
          S.pickApnea = a; S.pickBin = null; S.pickMark = null; draw(); drawPick();
          lst.querySelectorAll('button').forEach(function (b, j) { b.setAttribute('aria-pressed', j === i ? 'true' : 'false'); });
        } }, [tl.apnea.length <= 9 ? h('span', { 'class': 'chip-no', text: String(i + 1) }) : null, a.label + ' · ' + a.sec + ' 秒']));
      });
      c.appendChild(lst);
      if (tl.apnea.length > 12) c.appendChild(h('div', { 'class': 'caption muted', style: 'margin-top:6px', text: '还有 ' + (tl.apnea.length - 12) + ' 次，在图上直接点菱形查看。' }));
    }

    c.appendChild(h('div', { 'class': 'howto body-md' }, [h('b', { text: '怎么读：' }),
      '每根柱子是 10 分钟。柱子越高，这 10 分钟里打鼾的时间越多；颜色越暖，声音越响（是相对大小，不是精确分贝）。上面那条是睡眠状态。红色菱形是鼾声突然中断 10 秒以上的地方，点一下可以看时间、听录音。']));
    return c;
  }

  function findClip(r, id) { for (var i = 0; i < r.clips.length; i++) if (r.clips[i].id === id) return r.clips[i]; return null; }

  function playBtn(id) {
    var on = S.playing === id;
    return h('button', { 'class': 'rec', style: 'padding:0;min-height:0;border:0', 'aria-label': on ? '停止播放' : '播放录音', onclick: function () { toggleClip(id); } },
      h('span', { 'class': 'play' + (on ? ' on' : '') }, icon(on ? 'pause' : 'play', 24)));
  }
  function toggleClip(id) {
    if (S.playing === id) { stopClip(); render(true); return; }
    var r = Y('playClip', id);
    if (r === 'ok') { S.playing = id; render(true); }
    else U.toast('这段录音已经被清除了，或者没有保存。');
  }
  function stopClip() { if (S.playing) { Y('stopClip'); S.playing = 0; } }

  function clipRow(c) {
    return h('div', { 'class': 'rec' }, [playBtn(c.id), h('div', { 'class': 'grow' }, [
      h('div', { 'class': 'label-lg', text: c.t + ' · ' + c.title }), c.desc ? h('div', { 'class': 'caption muted', text: c.desc }) : null]),
      h('div', { 'class': 'data-sm muted', text: c.sec + ' 秒' })]);
  }

  function trendCard(curId) {
    var list = S.nights.slice(0, 7).reverse();
    var c = card(h('p', { 'class': 'hint', text: '柱子是睡眠时长，下面的圆点是打鼾程度（灰：几乎没有，青：轻，琥珀：中，红：重）。点一下可以看那一晚。' }));
    var tr = h('div', { 'class': 'trend' });
    list.forEach(function (n) {
      var hh = Math.max(3, Math.min(1, n.sleepMin / 600) * 80);
      var cls = n.snorePct < 3 ? '' : n.snorePct < 10 ? 'l1' : n.snorePct < 30 ? 'l2' : 'l3';
      tr.appendChild(h('button', { 'aria-current': n.id === curId ? 'true' : 'false', 'aria-label': n.dateLabel + '，睡了' + n.sleepLabel + '，打鼾' + n.snoreWord, onclick: function () { go('report', n.id); } }, [
        h('div', { 'class': 'data-sm muted', text: n.sleepMin > 0 ? (Math.round(n.sleepMin / 6) / 10) + '' : '' }),
        h('div', { 'class': 'b', style: 'height:' + hh + 'px' }), h('div', { 'class': 'd ' + cls }), h('div', { 'class': 'caption muted', style: 'font-size:11px', text: n.short.replace('月', '/').replace('日', '') })]));
    });
    c.appendChild(tr);
    var sl = h('div', { 'class': 'trend', style: 'height:auto;margin-top:6px;align-items:flex-start' });
    var any = false;
    list.forEach(function (n) {
      var t = n.sleepy == null ? '' : n.sleepy >= 4 ? '困' : n.sleepy >= 2.5 ? '一般' : '清醒';
      if (t) any = true;
      sl.appendChild(h('div', { style: 'flex:1;text-align:center', 'class': 'caption ' + (n.sleepy >= 4 ? 'accent-text' : 'muted'), text: t }));
    });
    if (any) {
      c.appendChild(h('div', { 'class': 'caption muted', style: 'margin-top:8px', text: '第二天白天自评：' }));
      c.appendChild(sl);
    }
    return c;
  }

  function detailBody(r) {
    var el = h('div');
    r.detail.rows.forEach(function (k) { el.appendChild(h('div', { 'class': 'kv body-md' }, [h('span', { text: k.k }), h('span', { text: k.v })])); });
    if (r.detail.scoreParts.length) {
      el.appendChild(h('div', { 'class': 'label-lg', style: 'margin:16px 0 4px', text: '评分是怎么算的' }));
      r.detail.scoreParts.forEach(function (p) { el.appendChild(h('div', { 'class': 'kv body-md' }, [h('span', { text: p.name }), h('span', { 'class': 'data-sm', text: p.got + ' / ' + p.max })])); });
    }
    el.appendChild(h('div', { 'class': 'label-lg', style: 'margin:16px 0 4px', text: '这些数字是怎么来的' }));
    el.appendChild(h('p', { 'class': 'body-md muted', style: 'margin:0', text: '鼾声和呼吸暂停：手机上运行的声音识别模型（Google YAMNet）先认出“这是不是鼾声”，再结合规则判断；“疑似呼吸暂停”是鼾声连续出现后突然安静了 10 秒以上。手机听不到气流和血氧，所以只叫“疑似”。' }));
    el.appendChild(h('p', { 'class': 'body-md muted', text: '睡着和醒来：根据手机感觉到的动静、屏幕亮起和声音来估算。没有心率，分不出快速眼动睡眠，深睡只是粗略估计。声音大小是相对值，没有经过校准。' }));
    el.appendChild(h('div', { style: 'margin-top:12px' }, btn('删除这一晚的记录', 'danger block', function () {
      U.confirmBox('删除这一晚？', '这一晚的统计、事件和录音片段都会被删除，无法恢复。', '删除', function () { stopClip(); Y('deleteNight', r.id); S.rid = 0; S.dirty = true; render(); }, '取消', true); })));
    return el;
  }

  function nightsSheet() {
    U.openSheet(function (sheet, close) {
      sheet.appendChild(h('h2', { 'class': 'headline-md', style: 'margin-bottom:8px', text: '所有记录' }));
      S.nights.forEach(function (n) {
        sheet.appendChild(h('button', { 'class': 'row list-row', style: 'width:100%;text-align:left', onclick: function () { close(); go('report', n.id); } }, [
          h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: n.dateLabel }),
            h('div', { 'class': 'caption muted', text: n.tooShort ? '记录较短' : '睡了 ' + n.sleepLabel + ' · 打鼾' + n.snoreWord + (n.apnea ? ' · 疑似呼吸暂停 ' + n.apnea + ' 次' : '') })]),
          h('div', { 'class': 'data-lg accent-text', text: n.score >= 0 ? String(n.score) : '--' }), icon('right', 20)]));
      });
      sheet.appendChild(btn('关闭', 'tonal block', close));
    });
  }

  // ================================================================= 我的
  function viewMe() {
    var env = S.env, p = YJ('prefs', {});
    var page = h('div', { 'class': 'page fade' });
    page.appendChild(h('div', { 'class': 'topbar' }, h('div', { 'class': 'headline-md', text: '我的' })));

    // 帮助
    var help = card([h('h2', { text: '使用帮助' })]);
    [['重新看新手引导', function () { S.onb = 0; S.skipIntro = false; render(); }],
     ['手机怎么放', placementSheet],
     ['让夜莺整晚不被关掉', batterySheet],
     ['常见问题', faqSheet]].forEach(function (x) {
      help.appendChild(h('button', { 'class': 'row list-row', style: 'width:100%', onclick: x[1] }, [h('div', { 'class': 'grow label-lg', text: x[0] }), icon('right', 20)]));
    });
    page.appendChild(help);

    // 检测
    var det = card([h('h2', { text: '检测' })]);
    det.appendChild(h('div', { 'class': 'label-lg', style: 'margin-top:8px', text: '鼾声灵敏度' }));
    var senseHint = h('p', { 'class': 'caption muted', style: 'margin:4px 0 8px' });
    var senseText = ['只记录比较明显的鼾声。漏记多就调高。', '推荐。大多数情况下够用。', '连很轻的鼾声也记录，但更容易把别的声音当成鼾声。'];
    senseHint.textContent = senseText[p.sens];
    det.appendChild(seg(['低', '中', '高'], p.sens, function (i) { Y('setPref', 'sens', i); senseHint.textContent = senseText[i]; }));
    det.appendChild(senseHint);
    det.appendChild(h('div', { 'class': 'hr' }));
    det.appendChild(h('div', { 'class': 'label-lg', text: '目标睡眠时长' }));
    var goal = h('div', { 'class': 'data-lg', text: U.hm(p.goalMin) });
    det.appendChild(h('div', { 'class': 'row' }, [h('button', { 'class': 'icon-btn', 'aria-label': '减少 15 分钟', onclick: function () { p.goalMin = Math.max(300, p.goalMin - 15); Y('setPref', 'goal', p.goalMin); goal.textContent = U.hm(p.goalMin); } }, h('span', { 'class': 'title', text: '−' })),
      h('div', { 'class': 'grow', style: 'text-align:center' }, goal),
      h('button', { 'class': 'icon-btn', 'aria-label': '增加 15 分钟', onclick: function () { p.goalMin = Math.min(720, p.goalMin + 15); Y('setPref', 'goal', p.goalMin); goal.textContent = U.hm(p.goalMin); } }, h('span', { 'class': 'title', text: '+' }))]));
    det.appendChild(h('div', { 'class': 'hr' }));
    det.appendChild(toggleRow('检测磨牙（实验功能）', '还不太准：翻身时床和被子的摩擦声可能被当成磨牙。不需要可以关掉。', p.grind, function (v) { Y('setPref', 'grind', v ? 1 : 0); }));
    det.appendChild(h('div', { 'class': 'hr' }));
    det.appendChild(toggleRow('床头屏常亮', '监测时屏幕保持最低亮度，方便看时间。默认关闭，让屏幕自己熄灭更省电。', p.bedsideOn, function (v) { Y('setPref', 'bedside', v ? 1 : 0); }));
    page.appendChild(det);

    // 录音与隐私
    var pr = card([h('h2', { text: '录音与隐私' }), h('p', { 'class': 'hint', text: '所有数据只存在这部手机上。夜莺没有联网权限。' })]);
    pr.appendChild(toggleRow('保存事件录音片段', '鼾声、疑似呼吸暂停等事件前后几秒，用来回放核对。关掉后不保存任何录音。', p.saveClips, function (v) { Y('setPref', 'clips', v ? 1 : 0); }));
    pr.appendChild(h('div', { 'class': 'hr' }));
    pr.appendChild(h('div', { 'class': 'label-lg', text: '录音保留天数' }));
    var days = [3, 7, 14, 30];
    pr.appendChild(h('div', { style: 'margin-top:8px' }, seg(days.map(function (d) { return d + ' 天'; }), days.indexOf(p.clipDays), function (i) { Y('setPref', 'clipDays', days[i]); })));
    var sz = p.clipBytes < 1048576 ? Math.round(p.clipBytes / 1024) + ' KB' : (p.clipBytes / 1048576).toFixed(1) + ' MB';
    pr.appendChild(h('div', { 'class': 'row' }, [h('div', { 'class': 'grow body-md muted', text: '现在占用 ' + sz }),
      btn('清除录音', 'text', function () { U.confirmBox('清除所有录音片段？', '统计和事件记录会保留，只是不能再回放。', '清除', function () { Y('clearClips'); render(true); }, '取消', true); })]));
    page.appendChild(pr);

    // 数据
    var dc = card([h('h2', { text: '数据' })]);
    dc.appendChild(btn('删除全部数据', 'danger block', function () {
      U.confirmBox('删除全部数据？', '所有夜晚的记录、事件、录音片段和白天自评都会被删除，无法恢复。', '全部删除', function () { stopClip(); Y('deleteAll'); S.rid = 0; S.dirty = true; U.toast('已删除'); render(true); }, '取消', true); }));
    page.appendChild(dc);

    // 高级
    page.appendChild(accordion('adv', '高级', function () {
      var el = h('div');
      el.appendChild(h('div', { 'class': 'kv body-md' }, [h('span', { text: '声音识别' }), h('span', { text: env.modelFile ? 'YAMNet 模型，在本机运行' : '未找到模型' })]));
      el.appendChild(h('div', { 'class': 'kv body-md' }, [h('span', { text: '麦克风模式' }), h('span', { text: env.audioSource })]));
      el.appendChild(h('div', { style: 'display:flex;flex-direction:column;gap:8px;margin-top:12px' }, [
        btn('导出原始数据（CSV）', 'tonal block', function () {
          var r = YJ('exportCsv', {}); U.confirmBox(r.ok ? '已导出' : '导出失败', r.ok ? '已保存两个文件：每晚汇总、全部声音事件。\n位置：' + r.where : (r.err || ''), '好的', null, null, false); }),
        btn('分享诊断信息（出问题时用）', 'tonal block', function () { Y('shareDiag'); })]));
      return el;
    }));

    // 关于
    var ab = card([h('h2', { text: '关于夜莺 ' + (env.version || '') })]);
    ab.appendChild(h('p', { 'class': 'body-md muted', text: '夜莺是日常记录工具，估算值不能替代医生的诊断。手机听不到气流和血氧，“疑似呼吸暂停”只是提醒。如果响亮的鼾声加上白天犯困，建议去睡眠门诊或呼吸科。白天觉得困时请不要开车。' }));
    ab.appendChild(h('p', { 'class': 'caption muted', text: '声音识别使用 Google 的 YAMNet 模型（Apache 2.0 许可），图标来自 Google 的 Material Design 图标（Apache 2.0）。模型与全部分析都在手机上运行。' }));
    page.appendChild(ab);
    return page;
  }

  function seg(labels, sel, on) {
    var box = h('div', { 'class': 'seg', role: 'group' });
    labels.forEach(function (t, i) {
      box.appendChild(h('button', { 'aria-pressed': i === sel ? 'true' : 'false', onclick: function () {
        box.querySelectorAll('button').forEach(function (b, j) { b.setAttribute('aria-pressed', j === i ? 'true' : 'false'); });
        on(i);
      } }, t));
    });
    return box;
  }
  function toggleRow(title, sub, on, cb) {
    var sw = h('button', { 'class': 'switch', role: 'switch', 'aria-checked': on ? 'true' : 'false', 'aria-label': title });
    var v = !!on;
    sw.addEventListener('click', function () { v = !v; sw.setAttribute('aria-checked', v ? 'true' : 'false'); cb(v); });
    return h('div', { 'class': 'row top' }, [h('div', { 'class': 'grow' }, [h('div', { 'class': 'label-lg', text: title }), h('div', { 'class': 'caption muted', text: sub })]), sw]);
  }

  function batterySheet() {
    U.openSheet(function (sheet, close) {
      sheet.appendChild(h('h2', { 'class': 'headline-md', style: 'margin-bottom:8px', text: '让夜莺整晚不被关掉' }));
      sheet.appendChild(h('p', { 'class': 'body-md muted', text: '很多手机为了省电，锁屏后会把后台程序清理掉。先点下面的按钮选“允许”，再按你的手机品牌检查一下。睡前接上充电线。' }));
      sheet.appendChild(btn('去设置', 'primary block', function () { Y('requestIgnoreBattery'); }));
      BRANDS.forEach(function (b) { sheet.appendChild(h('div', { style: 'padding:10px 0' }, [h('div', { 'class': 'label-lg', text: b[0] }), h('div', { 'class': 'body-md muted', text: b[1] })])); });
      sheet.appendChild(h('div', { 'class': 'caption muted', text: '其他机型可以搜索“dontkillmyapp”。' }));
      sheet.appendChild(h('div', { style: 'height:12px' }));
      sheet.appendChild(btn('关闭', 'tonal block', close));
    });
  }
  function faqSheet() {
    var Q = [
      ['为什么报告里没有“快速眼动”“深睡”很多？', '夜莺只靠手机的动静和声音，没有心率，分不出快速眼动睡眠，深睡也只是粗略估计。别太在意这两项，多看睡了多久、打鼾和疑似呼吸暂停。'],
      ['“疑似呼吸暂停”是不是说明我有呼吸暂停综合征？', '不是。手机只听得到声音，听不到气流和血氧。它的意思是：鼾声连续出现后，突然安静了 10 秒以上才又响起。偶尔一次很常见；连续几晚都有，或者白天很困，建议去睡眠门诊做专业监测。'],
      ['鼾声漏记了或者记多了怎么办？', '在“我的”里调整灵敏度。回放报告里的录音片段，可以自己核对判断得对不对。'],
      ['夜里监测被中断了？', '多半是手机为了省电关掉了夜莺。按“让夜莺整晚不被关掉”设置，并在睡前接上充电线。'],
      ['为什么磨牙几乎没检测到？', '磨牙检测还是实验功能：它靠声音识别模型加规则，没有在真实的磨牙录音上验证过，所以可能漏掉，也可能把别的摩擦声当成磨牙。']];
    U.openSheet(function (sheet, close) {
      sheet.appendChild(h('h2', { 'class': 'headline-md', style: 'margin-bottom:8px', text: '常见问题' }));
      Q.forEach(function (q) { sheet.appendChild(h('div', { style: 'padding:10px 0' }, [h('div', { 'class': 'label-lg', text: q[0] }), h('div', { 'class': 'body-md muted', text: q[1] })])); });
      sheet.appendChild(btn('关闭', 'tonal block', close));
    });
  }

  // ================================================================= Java 回调与轮询
  window.YH_onEvent = function (name, payload) {
    var p = null; try { p = payload ? JSON.parse(payload) : null; } catch (e) { }
    if (name === 'perm') {
      S.env = YJ('env', S.env);
      var f = S.afterPerm; S.afterPerm = null;
      if (S.env.hasMic && f) { f(); return; }
      render(true);
    } else if (name === 'resume' || name === 'theme') {
      if (!(S.st.running || S.st.waiting)) render(true);
    } else if (name === 'clipDone') {
      S.playing = 0; if (S.tab === 'report') render(true);
    }
  };
  window.YH_back = function () {
    if (window.YH_sheetClose) { window.YH_sheetClose(); return true; }
    if (S.onb > 0) { stopMic(); S.onb--; render(); return true; }
    if (S.onb === 0) return false;
    if (S.st.running || S.st.waiting) return false;
    if (S.tab !== 'tonight') { go('tonight'); return true; }
    return false;
  };

  var lastErr = null;
  function poll() {
    var prev = S.st || {};
    var st = YJ('state', prev);
    S.st = st;
    var was = !!(prev.running || prev.waiting), now = !!(st.running || st.waiting);
    if (S.seenFinished === -1) S.seenFinished = st.finishedNight || 0;
    if (st.finishedNight && st.finishedNight !== S.seenFinished) {
      S.seenFinished = st.finishedNight;
      S.tab = 'report'; S.rid = st.finishedNight; S.fresh = true; S.dirty = true;
      render();
      if (st.stopReason) U.confirmBox('监测已结束', st.stopReason, '知道了', null, null, false);
      return;
    }
    if (!st.startError) lastErr = null;
    if (st.startError && st.startError !== lastErr) {
      lastErr = st.startError; render();
      U.confirmBox('没能开始监测', st.startError, '知道了', null, null, false);
      return;
    }
    if (was !== now) { render(); return; }
    if (now) updateBedside();
  }

  function boot() {
    if (!window.YH && !window.YH_MOCK_OK && boot.n++ < 20) { setTimeout(boot, 100); return; }
    render();
    setInterval(poll, 1000);
  }
  boot.n = 0;
  window.YH_render = render;
  window.YH_state = S;
  boot();
})();
