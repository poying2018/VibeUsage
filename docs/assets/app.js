// ==================== Token Manager Full SPA ====================

const API = '/api';
let currentUser = null;
let platforms = [];
let trendChart = null;
let isAdmin = false;

// ==================== API Helpers ====================

async function api(path, options = {}) {
  const res = await fetch(API + path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    credentials: 'include'
  });
  if (res.status === 401) { showLogin(); throw new Error('Unauthorized'); }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Request failed');
  return data;
}

function toast(message, type = 'info') {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.className = 'toast ' + type + ' show';
  setTimeout(() => el.classList.remove('show'), 3000);
}

// ==================== Auth ====================

async function checkAuth() {
  try {
    currentUser = await api('/auth/me');
    isAdmin = !!currentUser.is_admin;
    showApp();
  } catch { showLogin(); }
}

async function logout() {
  try { await api('/auth/logout', { method: 'POST' }); } catch {}
  currentUser = null; isAdmin = false; showLogin();
}

function showLogin() {
  document.getElementById('loginScreen').style.display = 'flex';
  document.getElementById('app').style.display = 'none';
}

function showApp() {
  document.getElementById('loginScreen').style.display = 'none';
  document.getElementById('app').style.display = 'flex';
  document.getElementById('userAvatar').src = currentUser.avatar_url || '';
  document.getElementById('userName').textContent = currentUser.name || currentUser.login;
  document.getElementById('userLogin').textContent = '@' + currentUser.login;
  document.getElementById('navAdmin').style.display = isAdmin ? 'flex' : 'none';
  const now = new Date();
  document.getElementById('todayDate').textContent = now.getFullYear() + '年' + (now.getMonth()+1) + '月' + now.getDate() + '日';
  loadPlatforms();
  loadDashboard();
}

// ==================== Navigation ====================

function switchPage(pageName) {
  document.querySelectorAll('.nav-item').forEach(el => el.classList.toggle('active', el.dataset.page === pageName));
  document.querySelectorAll('.page').forEach(el => el.classList.toggle('active', el.id === 'page-' + pageName));
  if (pageName === 'dashboard') loadDashboard();
  if (pageName === 'records') loadRecords();
  if (pageName === 'asset') loadAsset();
  if (pageName === 'value') loadValue();
  if (pageName === 'cost') loadCost();
  if (pageName === 'ai') loadAI();
  if (pageName === 'proxy') loadProxy();
  if (pageName === 'admin') loadAdmin();
  if (pageName === 'settings') { loadPlatforms(); renderPlatformsTable(); }
}

// ==================== Dashboard ====================

async function loadDashboard() {
  try {
    const stats = await api('/stats');
    animateValue('metricMonthTokens', stats.monthly.total_tokens);
    animateValue('metricTodayTokens', stats.today.total_tokens);
    document.getElementById('metricMonthCost').textContent = '¥' + (stats.monthly.total_cost||0).toFixed(2);
    const activeP = stats.byPlatform.filter(p => p.total_tokens > 0).length;
    document.getElementById('metricPlatforms').textContent = activeP;
    document.getElementById('metricRecords').textContent = stats.monthly.record_count || 0;
    renderPlatformList(stats.byPlatform);
    renderTrendChart(stats.dailyTrend);
    const records = await api('/records?limit=5');
    renderRecentTable(records);
  } catch (e) { console.error('Dashboard:', e); }
}

function renderPlatformList(byPlatform) {
  const container = document.getElementById('platformList');
  const max = Math.max(...byPlatform.map(p => p.total_tokens), 1);
  container.innerHTML = byPlatform.map(p => {
    const pct = max > 0 ? (p.total_tokens / max * 100) : 0;
    return '<div class="platform-item"><div class="platform-bar-wrap">' +
      '<div class="platform-bar-fill" style="width:'+pct+'%;background:'+(p.color||'#ffd700')+'"></div>' +
      '<span class="platform-bar-label">'+(p.icon||'🔮')+' '+p.name+'</span>' +
      '<span class="platform-bar-value">'+formatTokens(p.total_tokens)+'</span>' +
      '</div><span class="platform-cost">¥'+(p.total_cost||0).toFixed(2)+'</span></div>';
  }).join('');
}

function renderTrendChart(dailyTrend) {
  const ctx = document.getElementById('trendChart');
  if (!ctx) return;
  if (trendChart) trendChart.destroy();
  const labels = dailyTrend.map(d => d.date.slice(5));
  const tokens = dailyTrend.map(d => d.tokens);
  const costs = dailyTrend.map(d => d.cost);
  trendChart = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: labels.length ? labels : ['暂无数据'],
      datasets: [{
        label: 'Token', data: tokens.length ? tokens : [0],
        backgroundColor: 'rgba(0,212,255,0.55)', borderColor: 'rgba(0,212,255,1)', borderWidth: 1, borderRadius: 4, yAxisID: 'y'
      }, {
        label: '费用 (¥)', data: costs.length ? costs : [0],
        type: 'line', borderColor: '#ffd700', backgroundColor: 'rgba(255,215,0,0.08)',
        borderWidth: 2, pointRadius: 3, pointBackgroundColor: '#ffd700', fill: true, tension: 0.4, yAxisID: 'y1'
      }]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: { legend: { labels: { color: '#8b9dc3', font: { size: 12 } } } },
      scales: {
        x: { grid: { color: 'rgba(255,255,255,0.03)' }, ticks: { color: '#8b9dc3', font: { size: 11 } } },
        y: { position: 'left', grid: { color: 'rgba(255,255,255,0.03)' }, ticks: { color: '#8b9dc3' } },
        y1: { position: 'right', grid: { drawOnChartArea: false }, ticks: { color: '#ffd700' } }
      }
    }
  });
}

function renderRecentTable(records) {
  const tbody = document.getElementById('recentTbody');
  const empty = document.getElementById('emptyRecent');
  if (!records.length) { tbody.innerHTML = ''; empty.style.display = 'block'; return; }
  empty.style.display = 'none';
  tbody.innerHTML = records.map(r => '<tr>' +
    '<td><span class="platform-tag" style="background:'+(r.platform_color||'#ffd700')+'20;color:'+(r.platform_color||'#ffd700')+'">'+(r.platform_icon||'🔮')+' '+r.platform_name+'</span></td>' +
    '<td>'+(r.model||'—')+'</td><td>'+formatTokens(r.input_tokens)+'</td>' +
    '<td>'+formatTokens(r.output_tokens)+'</td><td>¥'+(r.cost||0).toFixed(2)+'</td>' +
    '<td style="color:#8b9dc3">'+formatTime(r.created_at)+'</td></tr>').join('');
}

// ==================== Records ====================

async function loadRecords() {
  const records = await api('/records?limit=100');
  renderRecordsTable(records);
}

function renderRecordsTable(records) {
  const tbody = document.getElementById('recordsTbody');
  const empty = document.getElementById('emptyRecords');
  document.getElementById('recordCount').textContent = '共 ' + records.length + ' 条';
  if (!records.length) { tbody.innerHTML = ''; empty.style.display = 'block'; return; }
  empty.style.display = 'none';
  tbody.innerHTML = records.map(r => '<tr>' +
    '<td><span class="platform-tag" style="background:'+(r.platform_color||'#ffd700')+'20;color:'+(r.platform_color||'#ffd700')+'">'+(r.platform_icon||'🔮')+' '+r.platform_name+'</span></td>' +
    '<td>'+(r.model||'—')+'</td><td style="color:#8b9dc3">'+(r.project||'—')+'</td>' +
    '<td>'+formatTokens(r.input_tokens)+'</td><td>'+formatTokens(r.output_tokens)+'</td>' +
    '<td>¥'+(r.cost||0).toFixed(2)+'</td><td style="color:#8b9dc3">'+formatTime(r.created_at)+'</td>' +
    '<td><button class="btn-danger" onclick="deleteRecord('+r.id+')">🗑️</button></td></tr>').join('');
}

async function submitRecord(e) {
  e.preventDefault();
  const createdAt = document.getElementById('formDate').value;
  const body = {
    platform_id: parseInt(document.getElementById('formPlatform').value),
    model: document.getElementById('formModel').value || null,
    project: document.getElementById('formProject').value || null,
    input_tokens: parseInt(document.getElementById('formInputTokens').value) || 0,
    output_tokens: parseInt(document.getElementById('formOutputTokens').value) || 0,
    cost: parseFloat(document.getElementById('formCost').value) || 0,
    note: document.getElementById('formNote').value || null,
    created_at: createdAt ? new Date(createdAt).toISOString() : new Date().toISOString()
  };
  try {
    await api('/records', { method: 'POST', body: JSON.stringify(body) });
    toast('记录已保存', 'success');
    document.getElementById('entryForm').reset();
    loadRecords();
  } catch (e) { toast('保存失败：' + e.message, 'error'); }
}

async function deleteRecord(id) {
  if (!confirm('确定删除？')) return;
  try { await api('/records/'+id, { method: 'DELETE' }); toast('已删除', 'success'); loadRecords(); }
  catch (e) { toast('删除失败：' + e.message, 'error'); }
}

// ==================== Asset ====================

async function loadAsset() {
  try {
    const all = await api('/records?limit=10000');
    const records = all || [];
    const totalTokens = records.reduce((s, r) => s + r.input_tokens + r.output_tokens, 0);
    const totalCost = records.reduce((s, r) => s + (r.cost || 0), 0);
    const days = records.length ? Math.max(1, (Date.now() - new Date(records[records.length-1].created_at)) / 86400000) : 1;
    const dailyAvg = Math.round(totalTokens / days);
    
    document.getElementById('assetTotalTokens').textContent = formatTokens(totalTokens);
    document.getElementById('assetTotalCost').textContent = '¥' + totalCost.toFixed(2);
    document.getElementById('assetDailyAvg').textContent = formatTokens(dailyAvg);
    
    // Platform breakdown
    const pMap = {};
    records.forEach(r => {
      const k = r.platform_name;
      if (!pMap[k]) pMap[k] = { name: k, icon: r.platform_icon, color: r.platform_color, tokens: 0, cost: 0, count: 0 };
      pMap[k].tokens += r.input_tokens + r.output_tokens;
      pMap[k].cost += r.cost || 0;
      pMap[k].count++;
    });
    const pList = Object.values(pMap).sort((a,b) => b.tokens - a.tokens);
    document.getElementById('assetTopPlatform').textContent = pList[0]?.name || '—';
    
    const tbody = document.getElementById('assetTbody');
    tbody.innerHTML = pList.map(p => {
      const pct = totalTokens > 0 ? (p.tokens / totalTokens * 100).toFixed(1) : 0;
      return '<tr><td><span class="platform-tag" style="background:'+p.color+'20;color:'+p.color+'">'+(p.icon||'🔮')+' '+p.name+'</span></td>' +
        '<td>'+formatTokens(p.tokens)+'</td><td>¥'+p.cost.toFixed(2)+'</td>' +
        '<td>'+p.count+'</td><td>'+pct+'%</td></tr>';
    }).join('');
  } catch (e) { console.error('Asset:', e); }
}

// ==================== Value ====================

async function loadValue() {
  try {
    const all = await api('/records?limit=10000');
    const records = all || [];
    const totalTokens = records.reduce((s, r) => s + r.input_tokens + r.output_tokens, 0);
    const totalCost = records.reduce((s, r) => s + (r.cost || 0), 0);
    const costPerMillion = totalTokens > 0 ? (totalCost / totalTokens * 1000000).toFixed(4) : '0';
    document.getElementById('valueCostPerToken').textContent = '¥' + costPerMillion;
    
    // Growth (compare last month vs previous month)
    const now = new Date();
    const lmStart = new Date(now.getFullYear(), now.getMonth()-1, 1).toISOString();
    const lmEnd = new Date(now.getFullYear(), now.getMonth(), 1).toISOString();
    const pmStart = new Date(now.getFullYear(), now.getMonth()-2, 1).toISOString();
    const pmEnd = new Date(now.getFullYear(), now.getMonth()-1, 1).toISOString();
    
    const lmRecords = records.filter(r => r.created_at >= lmStart && r.created_at < lmEnd);
    const pmRecords = records.filter(r => r.created_at >= pmStart && r.created_at < pmEnd);
    const lmTokens = lmRecords.reduce((s,r) => s + r.input_tokens + r.output_tokens, 0);
    const pmTokens = pmRecords.reduce((s,r) => s + r.input_tokens + r.output_tokens, 0);
    const growth = pmTokens > 0 ? ((lmTokens - pmTokens) / pmTokens * 100).toFixed(1) : '0';
    const growthEl = document.getElementById('valueGrowth');
    growthEl.textContent = (lmTokens >= pmTokens ? '+' : '') + growth + '%';
    growthEl.style.color = lmTokens >= pmTokens ? '#ff4757' : '#00ff88';
    
    // Platform value comparison
    const pMap = {};
    records.forEach(r => {
      const k = r.platform_name;
      if (!pMap[k]) pMap[k] = { name: k, icon: r.platform_icon, color: r.platform_color, tokens: 0, cost: 0 };
      pMap[k].tokens += r.input_tokens + r.output_tokens;
      pMap[k].cost += r.cost || 0;
    });
    const pList = Object.values(pMap).map(p => ({
      ...p,
      unitCost: p.tokens > 0 ? p.cost / p.tokens * 1000000 : 999
    })).sort((a,b) => a.unitCost - b.unitCost);
    
    const cheapest = pList[0];
    document.getElementById('valueCheapest').textContent = cheapest ? cheapest.name : '—';
    document.getElementById('valueBestValue').textContent = cheapest ? cheapest.name : '—';
    
    // Price comparison table from platforms data
    const tbody = document.getElementById('valuePlatformTbody');
    tbody.innerHTML = platforms.map(p => {
      const rating = p.input_price <= 1 ? '⭐⭐⭐⭐⭐ 极高' :
                     p.input_price <= 4 ? '⭐⭐⭐⭐ 高' :
                     p.input_price <= 10 ? '⭐⭐⭐ 中' :
                     p.input_price <= 25 ? '⭐⭐ 中低' : '⭐ 低';
      const color = p.input_price <= 1 ? 'price-low' : p.input_price <= 10 ? '' : 'price-highlight';
      return '<tr><td><span class="platform-tag" style="background:'+p.color+'20;color:'+p.color+'">'+(p.icon||'🔮')+' '+p.name+'</span></td>' +
        '<td class="'+color+'">¥'+p.input_price+'</td><td class="'+color+'">¥'+p.output_price+'</td>' +
        '<td class="'+color+'">'+rating+'</td></tr>';
    }).join('');
  } catch (e) { console.error('Value:', e); }
}

// ==================== Cost Planning ====================

async function loadCost() {
  try {
    const stats = await api('/stats');
    document.getElementById('costTotal').textContent = '¥' + (stats.monthly.total_cost||0).toFixed(2);
    const day = new Date().getDate();
    document.getElementById('costDaily').textContent = '¥' + ((stats.monthly.total_cost||0)/day).toFixed(2);
    const top = stats.byPlatform.sort((a,b) => (b.total_cost||0) - (a.total_cost||0))[0];
    document.getElementById('costTop').textContent = top?.name || '—';
    document.getElementById('costTopSub').textContent = top ? '¥'+(top.total_cost||0).toFixed(2) : '';
    const totalT = stats.monthly.total_tokens || 0;
    const totalC = stats.monthly.total_cost || 0;
    document.getElementById('costUnit').textContent = totalT > 0 ? '¥' + (totalC/totalT*1000000).toFixed(4) : '¥0.00';
    
    // Recommend cards
    const recs = [];
    const sorted = [...platforms].sort((a,b) => a.input_price - b.input_price);
    if (sorted[0]) recs.push({ type:'cost-first', label:'最省钱', platform:sorted[0], desc:'最低输入价格，适合大批量处理', saving:'¥'+((sorted[sorted.length-1]?.input_price||0) - sorted[0].input_price).toFixed(1)+'/百万Token' });
    const bestQuality = [...platforms].sort((a,b) => (b.quality_score||0) - (a.quality_score||0))[0];
    if (bestQuality) recs.push({ type:'quality-first', label:'最高质量', platform:bestQuality, desc:'最高质量评分，适合关键任务', saving:'评分 '+(bestQuality.quality_score||0)+'/5' });
    if (sorted[0] && sorted[1]) {
      const balanced = sorted.find(p => (p.quality_score||0) >= 4) || sorted[1];
      recs.push({ type:'balanced', label:'最佳平衡', platform:balanced, desc:'兼顾价格与质量，推荐日常使用', saving:'性价比最优' });
    }
    
    document.getElementById('recommendCards').innerHTML = recs.map(r =>
      '<div class="recommend-card '+r.type+'">' +
      '<div class="recommend-badge">'+r.label+'</div>' +
      '<div class="recommend-platform">'+(r.platform.icon||'🔮')+' '+r.platform.name+'</div>' +
      '<div class="recommend-price">¥'+r.platform.input_price+' / ¥'+r.platform.output_price+' (百万Token)</div>' +
      '<div class="recommend-saving">'+r.saving+'</div>' +
      '<div class="recommend-saving-label">'+r.desc+'</div>' +
      '</div>'
    ).join('');
    
    // Price table
    document.getElementById('priceTableBody').innerHTML = platforms.map(p => {
      const rating = p.input_price <= 1 ? '⭐⭐⭐⭐⭐ 极高' :
                     p.input_price <= 4 ? '⭐⭐⭐⭐ 高' :
                     p.input_price <= 10 ? '⭐⭐⭐ 中' :
                     p.input_price <= 25 ? '⭐⭐ 中低' : '⭐ 低';
      const color = p.input_price <= 1 ? 'price-low' : p.input_price <= 10 ? '' : 'price-highlight';
      return '<tr><td><span class="platform-tag" style="background:'+p.color+'20;color:'+p.color+'">'+(p.icon||'🔮')+' '+p.name+'</span></td>' +
        '<td class="'+color+'">¥'+p.input_price+'</td><td class="'+color+'">¥'+p.output_price+'</td>' +
        '<td class="'+color+'">'+rating+'</td></tr>';
    }).join('');
  } catch (e) { console.error('Cost:', e); }
}

// ==================== AI Chat ====================

let chatHistory = [];

async function loadAI() {
  try {
    chatHistory = await api('/chat');
    renderChat();
  } catch (e) { console.error('Chat load:', e); }
}

function renderChat() {
  const container = document.getElementById('aiMessages');
  let html = '<div class="ai-welcome-card"><h3>你好！我是 Token 管理助手</h3><p>我可以帮你分析 Token 消耗、提供成本优化建议、解答使用问题。</p></div>';
  chatHistory.forEach(m => {
    html += '<div class="ai-message-row '+m.role+'">' +
      '<div class="ai-avatar '+(m.role==='assistant'?'assistant':'')+'">'+(m.role==='assistant'?'🤖':'👤')+'</div>' +
      '<div class="ai-message-bubble">'+m.content.replace(/\n/g,'<br>')+'</div></div>';
  });
  container.innerHTML = html;
  container.scrollTop = container.scrollHeight;
}

async function sendChat(e) {
  e.preventDefault();
  const input = document.getElementById('aiInput');
  const msg = input.value.trim();
  if (!msg) return;
  input.value = '';
  chatHistory.push({ role:'user', content: msg });
  renderChat();
  // Add typing indicator
  const container = document.getElementById('aiMessages');
  container.innerHTML += '<div class="ai-message-row assistant"><div class="ai-avatar assistant">🤖</div><div class="ai-message-bubble"><span class="typing">思考中...</span></div></div>';
  container.scrollTop = container.scrollHeight;
  try {
    const { reply } = await api('/chat', { method:'POST', body: JSON.stringify({ message: msg }) });
    chatHistory.push({ role:'assistant', content: reply });
    renderChat();
  } catch (e) {
    chatHistory.push({ role:'assistant', content: '抱歉，服务暂时不可用。' });
    renderChat();
  }
}

function newChat() {
  api('/chat', { method:'DELETE' }).then(() => { chatHistory = []; renderChat(); });
}

// ==================== Proxy ====================

async function loadProxy() {
  try {
    const tokens = await api('/proxy');
    const tbody = document.getElementById('proxyTbody');
    const empty = document.getElementById('emptyProxy');
    if (!tokens.length) { tbody.innerHTML = ''; empty.style.display = 'block'; return; }
    empty.style.display = 'none';
    tbody.innerHTML = tokens.map(t => '<tr>' +
      '<td>'+(t.name||'—')+'</td>' +
      '<td style="font-family:monospace;font-size:12px;color:#8b9dc3">'+t.token+'</td>' +
      '<td><span style="color:'+(t.is_active?'#00ff88':'#ff4757')+'">'+(t.is_active?'活跃':'已禁用')+'</span></td>' +
      '<td style="color:#8b9dc3">'+formatTime(t.created_at)+'</td>' +
      '<td><button class="btn-danger" onclick="deleteProxy('+t.id+')">🗑️</button></td>' +
      '</tr>').join('');
  } catch (e) { console.error('Proxy:', e); }
}

async function addProxy(e) {
  e.preventDefault();
  const name = document.getElementById('proxyName').value || '默认代理';
  try {
    const result = await api('/proxy', { method:'POST', body: JSON.stringify({ name }) });
    toast('Token 已生成: ' + result.token.slice(0,12) + '...', 'success');
    document.getElementById('proxyForm').reset();
    loadProxy();
  } catch (e) { toast('生成失败：' + e.message, 'error'); }
}

async function deleteProxy(id) {
  if (!confirm('确定删除此代理 Token？')) return;
  try { await api('/proxy?id='+id, { method:'DELETE' }); toast('已删除', 'success'); loadProxy(); }
  catch (e) { toast('删除失败：' + e.message, 'error'); }
}

// ==================== Admin ====================

async function loadAdmin() {
  if (!isAdmin) return;
  try {
    const overview = await api('/admin?section=overview');
    document.getElementById('adminUserCount').textContent = overview.userCount || 0;
    document.getElementById('adminRecordCount').textContent = overview.recordCount || 0;
    document.getElementById('adminTotalCost').textContent = '¥' + (overview.totalCost || 0).toFixed(2);
    document.getElementById('adminFeedbackCount').textContent = overview.pendingFeedback || 0;
    
    const users = await api('/admin?section=users');
    document.getElementById('adminUserTbody').innerHTML = (users||[]).map(u => '<tr>' +
      '<td>'+u.id+'</td><td>'+(u.name||u.login)+'</td>' +
      '<td>'+ (u.plan||'free') +'</td>' +
      '<td style="color:#8b9dc3">'+formatTime(u.created_at)+'</td>' +
      '<td><button class="admin-reply-btn" onclick="toggleAdmin('+u.id+','+(u.is_admin?0:1)+')">'+(u.is_admin?'取消管理员':'设为管理员')+'</button></td>' +
      '</tr>').join('');
    
    const feedback = await api('/admin?section=feedback');
    document.getElementById('adminFeedbackTbody').innerHTML = (feedback||[]).map(f => '<tr>' +
      '<td>'+f.type+'</td><td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+f.content+'</td>' +
      '<td style="color:#8b9dc3">'+(f.user_login||'游客')+'</td>' +
      '<td><span class="admin-status-badge '+(f.status==='pending'?'admin-status-pending':'admin-status-replied')+'">'+(f.status==='pending'?'待处理':'已回复')+'</span></td>' +
      '<td style="color:#8b9dc3">'+formatTime(f.created_at)+'</td>' +
      '<td>'+(f.status==='pending'?'<button class="admin-reply-btn" onclick="replyFeedback('+f.id+')">回复</button>':'—')+'</td>' +
      '</tr>').join('');
  } catch (e) { console.error('Admin:', e); }
}

async function toggleAdmin(userId, val) {
  try {
    await api('/admin', { method:'POST', body: JSON.stringify({ action:'toggle_admin', userId, is_admin: !!val }) });
    toast('操作成功', 'success');
    loadAdmin();
  } catch (e) { toast('操作失败', 'error'); }
}

async function replyFeedback(id) {
  const reply = prompt('输入回复内容：');
  if (!reply) return;
  try {
    await api('/admin', { method:'POST', body: JSON.stringify({ action:'reply_feedback', id, reply }) });
    toast('回复已发送', 'success');
    loadAdmin();
  } catch (e) { toast('回复失败', 'error'); }
}

// ==================== Platforms ====================

async function loadPlatforms() {
  try {
    platforms = await api('/platforms');
    populatePlatformSelect();
    renderPlatformsTable();
  } catch (e) { console.error('Platforms:', e); }
}

function populatePlatformSelect() {
  const select = document.getElementById('formPlatform');
  select.innerHTML = platforms.map(p => '<option value="'+p.id+'">'+(p.icon||'🔮')+' '+p.name+'</option>').join('');
}

function renderPlatformsTable() {
  const tbody = document.getElementById('platformsTbody');
  tbody.innerHTML = platforms.map(p => '<tr>' +
    '<td><span style="font-size:20px">'+(p.icon||'🔮')+'</span></td>' +
    '<td>'+p.name+'</td>' +
    '<td style="font-size:12px;color:#8b9dc3">¥'+(p.input_price||0)+'</td>' +
    '<td style="font-size:12px;color:#8b9dc3">¥'+(p.output_price||0)+'</td>' +
    '<td>'+'⭐'.repeat(p.quality_score||3)+'</td>' +
    '</tr>').join('');
}

async function addPlatform(e) {
  e.preventDefault();
  const body = {
    name: document.getElementById('platName').value,
    icon: document.getElementById('platIcon').value || '🔮',
    color: document.getElementById('platColor').value,
    input_price: parseFloat(document.getElementById('platInputPrice').value) || 0,
    output_price: parseFloat(document.getElementById('platOutputPrice').value) || 0,
    quality_score: parseInt(document.getElementById('platQuality').value) || 3
  };
  try {
    await api('/platforms', { method:'POST', body: JSON.stringify(body) });
    toast('平台已添加', 'success');
    document.getElementById('platformForm').reset();
    loadPlatforms();
  } catch (e) { toast('添加失败：' + e.message, 'error'); }
}

// ==================== Feedback ====================

let feedbackType = 'suggestion';

function initFeedback() {
  document.getElementById('feedbackBtn').addEventListener('click', () => {
    document.getElementById('feedbackOverlay').classList.add('active');
  });
  document.getElementById('feedbackCancel').addEventListener('click', () => {
    document.getElementById('feedbackOverlay').classList.remove('active');
  });
  document.getElementById('feedbackOverlay').addEventListener('click', (e) => {
    if (e.target === e.currentTarget) e.currentTarget.classList.remove('active');
  });
  document.querySelectorAll('.feedback-type-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.feedback-type-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      feedbackType = btn.dataset.type;
    });
  });
  document.getElementById('feedbackSubmit').addEventListener('click', async () => {
    const content = document.getElementById('feedbackContent').value.trim();
    if (!content) { toast('请输入反馈内容', 'error'); return; }
    try {
      await api('/feedback', { method:'POST', body: JSON.stringify({
        type: feedbackType,
        content,
        contact: document.getElementById('feedbackContact').value || null
      })});
      toast('反馈已提交，感谢！', 'success');
      document.getElementById('feedbackContent').value = '';
      document.getElementById('feedbackContact').value = '';
      document.getElementById('feedbackOverlay').classList.remove('active');
    } catch (e) { toast('提交失败', 'error'); }
  });
}

// ==================== Utilities ====================

function formatTokens(n) {
  if (!n) return '0';
  if (n >= 1000000) return (n/1000000).toFixed(1)+'M';
  if (n >= 1000) return (n/1000).toFixed(1)+'K';
  return n.toString();
}

function formatTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const now = new Date();
  const diff = now - d;
  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return Math.floor(diff/60000)+' 分钟前';
  if (diff < 86400000) return Math.floor(diff/3600000)+' 小时前';
  return (d.getMonth()+1)+'-'+d.getDate()+' '+String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');
}

function animateValue(id, target) {
  const el = document.getElementById(id);
  if (!el) return;
  const start = parseInt(el.dataset.value || '0');
  const duration = 800;
  const startTime = performance.now();
  function update(now) {
    const elapsed = now - startTime;
    const progress = Math.min(elapsed / duration, 1);
    const eased = 1 - Math.pow(1 - progress, 3);
    const current = Math.round(start + (target - start) * eased);
    el.textContent = formatTokens(current);
    el.dataset.value = current;
    if (progress < 1) requestAnimationFrame(update);
  }
  requestAnimationFrame(update);
}

// ==================== Init ====================

document.addEventListener('DOMContentLoaded', () => {
  // Navigation
  document.querySelectorAll('[data-page]').forEach(el => {
    el.addEventListener('click', (e) => { e.preventDefault(); switchPage(el.dataset.page); });
  });
  
  // Logout
  document.getElementById('logoutBtn').addEventListener('click', logout);
  
  // Forms
  document.getElementById('entryForm').addEventListener('submit', submitRecord);
  document.getElementById('resetForm').addEventListener('click', () => document.getElementById('entryForm').reset());
  document.getElementById('platformForm').addEventListener('submit', addPlatform);
  document.getElementById('proxyForm').addEventListener('submit', addProxy);
  document.getElementById('aiForm').addEventListener('submit', sendChat);
  document.getElementById('aiNewChat').addEventListener('click', newChat);
  
  // Set default date
  const now = new Date();
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
  document.getElementById('formDate').value = now.toISOString().slice(0, 16);
  
  // Feedback
  initFeedback();
  
  // Check auth
  checkAuth();
});
