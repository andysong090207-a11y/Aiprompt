import { BANKS, bankById } from './banks.js';
import { extractAccounts, extractHolder, buildTossLink } from './parser.js';

const TESSERACT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js';
const RECENT_KEY = 'recent-accounts';

const $ = (id) => document.getElementById(id);
const els = {
  video: $('video'), guide: $('guide'), cameraMsg: $('camera-msg'), shutter: $('shutter'), file: $('file'), camfile: $('camfile'), manual: $('manual'),
  recent: $('recent'), recentList: $('recent-list'),
  preview: $('preview'), busyMsg: $('busy-msg'), busyProgress: $('busy-progress'),
  back: $('back'), candidates: $('candidates'), bank: $('bank'), bankHint: $('bank-hint'),
  account: $('account'), holder: $('holder'), amount: $('amount'),
  toss: $('toss'), copyAcc: $('copy-acc'), copyAll: $('copy-all'), rawText: $('raw-text'), toast: $('toast'),
};

let stream = null;

// ---------------- 화면 전환 ----------------
function show(id) {
  document.querySelectorAll('.screen').forEach((s) => s.classList.toggle('active', s.id === id));
  if (id === 'scan') startCamera();
  else stopCamera();
}

let toastTimer;
function toast(msg, ms = 2200) {
  els.toast.textContent = msg;
  els.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove('show'), ms);
}

// ---------------- 카메라 ----------------
async function startCamera() {
  if (stream) return;
  els.cameraMsg.hidden = true;
  if (!navigator.mediaDevices?.getUserMedia) {
    cameraError('이 브라우저에서는 카메라 미리보기를 쓸 수 없어요. 가운데 버튼이나 "사진 선택"으로 촬영하세요.');
    return;
  }
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } },
      audio: false,
    });
    els.video.srcObject = stream;
    await els.video.play().catch(() => {});
  } catch (e) {
    stream = null;
    cameraError('카메라 권한이 없어요. 브라우저 설정에서 허용하거나, 가운데 버튼으로 사진을 찍어 주세요.');
  }
}

function stopCamera() {
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  els.video.srcObject = null;
}

function cameraError(msg) {
  els.cameraMsg.textContent = msg;
  els.cameraMsg.hidden = false;
}

// 가이드 네모 영역만 잘라서 캔버스로 (video는 object-fit: cover)
function captureGuideArea() {
  const v = els.video;
  const vw = v.videoWidth, vh = v.videoHeight;
  const box = v.getBoundingClientRect();
  const g = els.guide.getBoundingClientRect();
  const scale = Math.max(box.width / vw, box.height / vh);
  const ox = (box.width - vw * scale) / 2;
  const oy = (box.height - vh * scale) / 2;
  // 가이드보다 살짝 넓게 잘라서 테두리에 걸친 글자도 포함
  const pad = 12;
  let sx = (g.left - box.left - ox - pad) / scale;
  let sy = (g.top - box.top - oy - pad) / scale;
  let sw = (g.width + pad * 2) / scale;
  let sh = (g.height + pad * 2) / scale;
  sx = Math.max(0, sx); sy = Math.max(0, sy);
  sw = Math.min(vw - sx, sw); sh = Math.min(vh - sy, sh);
  const c = document.createElement('canvas');
  c.width = Math.round(sw); c.height = Math.round(sh);
  c.getContext('2d').drawImage(v, sx, sy, sw, sh, 0, 0, c.width, c.height);
  return c;
}

async function fileToCanvas(file) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }).catch(async () => {
    const img = new Image();
    img.src = URL.createObjectURL(file);
    await img.decode();
    return img;
  });
  const c = document.createElement('canvas');
  c.width = bmp.width; c.height = bmp.height;
  c.getContext('2d').drawImage(bmp, 0, 0);
  return c;
}

// OCR 정확도를 위해 크기 조정 + 흑백 + 대비 늘리기
function preprocess(src) {
  const target = 1600;
  const longSide = Math.max(src.width, src.height);
  const k = longSide > 2400 ? 2400 / longSide : longSide < target ? target / longSide : 1;
  const c = document.createElement('canvas');
  c.width = Math.round(src.width * k); c.height = Math.round(src.height * k);
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(src, 0, 0, c.width, c.height);
  const img = ctx.getImageData(0, 0, c.width, c.height);
  const d = img.data;
  let min = 255, max = 0;
  const gray = new Uint8ClampedArray(d.length / 4);
  for (let i = 0, j = 0; i < d.length; i += 4, j++) {
    const y = 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2];
    gray[j] = y;
    if (y < min) min = y;
    if (y > max) max = y;
  }
  const range = Math.max(1, max - min);
  for (let i = 0, j = 0; i < d.length; i += 4, j++) {
    const y = ((gray[j] - min) * 255) / range;
    d[i] = d[i + 1] = d[i + 2] = y;
  }
  ctx.putImageData(img, 0, 0);
  return c;
}

// ---------------- OCR (Tesseract.js, 기기 안에서 처리) ----------------
let workerPromise = null;

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src; s.onload = resolve; s.onerror = () => reject(new Error('OCR 엔진을 불러오지 못했어요. 인터넷 연결을 확인하세요.'));
    document.head.appendChild(s);
  });
}

let progressHandler = () => {};
function getWorker() {
  if (!workerPromise) {
    workerPromise = (async () => {
      if (!window.Tesseract) await loadScript(TESSERACT_URL);
      const worker = await window.Tesseract.createWorker(['kor', 'eng'], 1, {
        logger: (m) => progressHandler(m),
      });
      await worker.setParameters({ preserve_interword_spaces: '1' });
      return worker;
    })();
    workerPromise.catch(() => { workerPromise = null; });
  }
  return workerPromise;
}

async function recognize(canvas) {
  els.preview.src = canvas.toDataURL('image/jpeg', 0.7);
  els.busyProgress.value = 0;
  els.busyMsg.textContent = '인식 엔진 준비 중… (처음 한 번만 조금 걸려요)';
  show('busy');
  progressHandler = (m) => {
    if (m.status === 'recognizing text') {
      els.busyMsg.textContent = '글자를 읽는 중…';
      els.busyProgress.value = m.progress;
    } else if (m.status?.includes('loading')) {
      els.busyMsg.textContent = '인식 엔진 준비 중… (처음 한 번만 조금 걸려요)';
      if (typeof m.progress === 'number') els.busyProgress.value = m.progress;
    }
  };
  try {
    const worker = await getWorker();
    const img = preprocess(canvas);
    // 가이드 안 글자는 한 덩어리 문단(6), 결과가 없으면 흩어진 글자 모드(11)로 한 번 더
    await worker.setParameters({ tessedit_pageseg_mode: '6' });
    let { data } = await worker.recognize(img);
    let text = data.text;
    if (extractAccounts(text).length === 0) {
      await worker.setParameters({ tessedit_pageseg_mode: '11' });
      ({ data } = await worker.recognize(img));
      text = `${text}\n${data.text}`;
    }
    showResult(text);
  } catch (e) {
    console.error(e);
    toast(e.message || '인식에 실패했어요');
    show('scan');
  }
}

// ---------------- 결과 화면 ----------------
function fillBankSelect() {
  els.bank.innerHTML = '<option value="">은행 선택</option>' +
    BANKS.map((b) => `<option value="${b.id}">${b.name}</option>`).join('');
}

function selectCandidate(c) {
  els.account.value = c.formatted;
  els.bank.value = c.bank || '';
  els.bankHint.textContent = c.bankSource === 'pattern' ? '(번호 형식으로 추정 — 확인 필요)' : c.bank ? '' : '(직접 선택해 주세요)';
}

function showResult(text) {
  const list = extractAccounts(text);
  els.rawText.textContent = text.trim() || '(인식된 글자 없음)';
  els.candidates.innerHTML = '';
  els.holder.value = extractHolder(text);
  els.amount.value = '';

  if (list.length === 0) {
    els.account.value = '';
    els.bank.value = '';
    els.bankHint.textContent = '';
    toast('계좌번호를 찾지 못했어요. 직접 입력하거나 다시 찍어 주세요.', 3000);
  } else {
    if (list.length > 1) {
      list.slice(0, 4).forEach((c, i) => {
        const b = document.createElement('button');
        b.className = 'chip' + (i === 0 ? ' on' : '');
        b.textContent = `${bankById(c.bank)?.name ?? '?'} ${c.formatted}`;
        b.onclick = () => {
          els.candidates.querySelectorAll('.chip').forEach((x) => x.classList.remove('on'));
          b.classList.add('on');
          selectCandidate(c);
        };
        els.candidates.appendChild(b);
      });
    }
    selectCandidate(list[0]);
  }
  show('result');
}

function currentInfo() {
  const digits = els.account.value.replace(/\D/g, '');
  const bank = bankById(els.bank.value);
  return { digits, bank, holder: els.holder.value.trim(), amount: els.amount.value.replace(/\D/g, '') };
}

function validate({ digits, bank }, needBank) {
  if (digits.length < 10 || digits.length > 16) {
    toast('계좌번호 자릿수를 확인해 주세요 (10~16자리)');
    els.account.focus();
    return false;
  }
  if (needBank && !bank) {
    toast('은행을 선택해 주세요');
    els.bank.focus();
    return false;
  }
  return true;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = document.createElement('textarea');
    ta.value = text; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    document.execCommand('copy');
    ta.remove();
  }
}

// ---------------- 최근 계좌 (이 기기에만 저장) ----------------
function loadRecent() {
  try { return JSON.parse(localStorage.getItem(RECENT_KEY)) || []; } catch { return []; }
}
function saveRecent(info) {
  const item = { bank: info.bank?.id || '', digits: info.digits, holder: info.holder, at: Date.now() };
  const list = [item, ...loadRecent().filter((r) => r.digits !== item.digits)].slice(0, 5);
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); } catch {}
  renderRecent();
}
function renderRecent() {
  const list = loadRecent();
  els.recent.hidden = list.length === 0;
  els.recentList.innerHTML = '';
  for (const r of list) {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.textContent = `${bankById(r.bank)?.name ?? ''} ${r.digits}`;
    if (r.holder) {
      const s = document.createElement('small');
      s.textContent = r.holder;
      b.appendChild(s);
    }
    b.onclick = () => {
      els.candidates.innerHTML = '';
      els.rawText.textContent = '';
      els.account.value = r.digits;
      els.bank.value = r.bank;
      els.bankHint.textContent = '';
      els.holder.value = r.holder || '';
      els.amount.value = '';
      show('result');
    };
    li.appendChild(b);
    els.recentList.appendChild(li);
  }
}

// ---------------- 이벤트 ----------------
els.shutter.onclick = () => {
  if (stream && els.video.videoWidth) recognize(captureGuideArea());
  else els.camfile.click(); // 미리보기가 없으면 기본 카메라 앱으로
};
for (const input of [els.file, els.camfile]) {
  input.onchange = async () => {
    const f = input.files?.[0];
    input.value = '';
    if (f) recognize(await fileToCanvas(f));
  };
}
els.manual.onclick = () => {
  els.candidates.innerHTML = '';
  els.rawText.textContent = '';
  els.account.value = ''; els.bank.value = ''; els.holder.value = ''; els.amount.value = '';
  els.bankHint.textContent = '';
  show('result');
  els.account.focus();
};
els.back.onclick = () => show('scan');

els.amount.oninput = () => {
  const n = els.amount.value.replace(/\D/g, '');
  els.amount.value = n ? Number(n).toLocaleString('ko-KR') : '';
};

els.toss.onclick = async () => {
  const info = currentInfo();
  if (!validate(info, true)) return;
  saveRecent(info);
  // 앱이 안 열릴 때를 대비해 계좌는 클립보드에도 넣어 둔다 (토스는 복사된 계좌를 자동 인식)
  await copyText(`${info.bank.name} ${info.digits}`);
  const link = buildTossLink({ bankToss: info.bank.toss, digits: info.digits, amount: info.amount });
  const t = setTimeout(() => {
    if (document.visibilityState === 'visible') toast('토스가 열리지 않으면 토스 앱을 직접 여세요. 계좌는 복사해 뒀어요.', 3500);
  }, 1800);
  document.addEventListener('visibilitychange', () => clearTimeout(t), { once: true });
  location.href = link;
};

els.copyAcc.onclick = async () => {
  const info = currentInfo();
  if (!validate(info, false)) return;
  await copyText(info.digits);
  saveRecent(info);
  toast(`복사됨: ${info.digits}`);
};

els.copyAll.onclick = async () => {
  const info = currentInfo();
  if (!validate(info, true)) return;
  const text = `${info.bank.name} ${info.digits}${info.holder ? ` ${info.holder}` : ''}`;
  await copyText(text);
  saveRecent(info);
  toast(`복사됨: ${text}`);
};

// ---------------- 시작 ----------------
fillBankSelect();
renderRecent();
show('scan');
// 첫 촬영을 빠르게: 화면이 뜨면 OCR 엔진을 미리 받아 둔다
setTimeout(() => getWorker().catch(() => {}), 500);

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
