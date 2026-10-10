// OCR 결과 텍스트에서 계좌번호와 은행을 추출하는 순수 함수 모음 (브라우저/Node 공용)
import { BANKS } from './banks.js';

const DASHES = /[‐‑‒–—―−_~=·•.]/g;

// OCR이 숫자를 문자로 잘못 읽는 경우 보정. 숫자가 많은 토큰에서만 적용한다.
const DIGIT_FIXES = { O: '0', o: '0', D: '0', Q: '0', I: '1', l: '1', '|': '1', '!': '1', i: '1', Z: '2', z: '2', S: '5', s: '5', B: '8', g: '9', b: '6', G: '6' };

export function normalizeLine(line) {
  return line
    .replace(DASHES, '-')
    .replace(/\S+/g, (tok) => {
      const digits = (tok.match(/\d/g) || []).length;
      const letters = tok.replace(/[\d-]/g, '').length;
      if (digits >= 4 && letters <= Math.max(2, digits / 2)) {
        return tok.replace(/[OoDQIl|!iZzSsBgbG]/g, (c) => DIGIT_FIXES[c]);
      }
      return tok;
    });
}

const PHONE_PREFIX = /^01[016789]/;

function isPhoneNumber(digits) {
  return PHONE_PREFIX.test(digits) && (digits.length === 10 || digits.length === 11);
}

// 은행 키워드 찾기: 긴 키워드 우선, 겹치는 매칭은 버림 (예: "KBANK" 안의 "KB")
export function findBankMentions(text) {
  const upper = text.toUpperCase();
  const hits = [];
  for (const bank of BANKS) {
    for (const [list, weight] of [[bank.strong, 3], [bank.weak, 1]]) {
      for (const alias of list) {
        const a = alias.toUpperCase();
        let idx = upper.indexOf(a);
        while (idx !== -1) {
          // 영문 약어는 앞뒤가 영문이면 단어 일부로 보고 무시 (예: "MG"가 "IMG" 안에 있는 경우)
          const ascii = /^[A-Z]+$/.test(a);
          const before = upper[idx - 1] || ' ';
          const after = upper[idx + a.length] || ' ';
          if (!ascii || (!/[A-Z]/.test(before) && !/[A-Z]/.test(after))) {
            hits.push({ bank, start: idx, end: idx + a.length, weight: weight + (alias.length >= 4 ? 1 : 0), len: a.length });
          }
          idx = upper.indexOf(a, idx + 1);
        }
      }
    }
  }
  hits.sort((x, y) => y.len - x.len || y.weight - x.weight);
  const taken = [];
  for (const h of hits) {
    if (!taken.some((t) => h.start < t.end && t.start < h.end)) taken.push(h);
  }
  return taken.sort((x, y) => x.start - y.start);
}

// 계좌번호 자릿수/형식으로 은행 추정 (키워드가 없을 때만 사용, 정확하지 않음)
export function guessBankByPattern(digits, formatted = '') {
  const groups = formatted.includes('-') ? formatted.split('-').map((g) => g.length).join('-') : '';
  const n = digits.length;
  if (n === 13 && digits.startsWith('3333')) return 'kakao';
  if (n === 13 && digits.startsWith('7979')) return 'kakao';
  if (n === 12 && digits.startsWith('1000') && (!groups || groups === '4-4-4')) return 'toss';
  if (n === 12 && digits.startsWith('100') && groups === '3-3-6') return 'kbank';
  if (n === 13 && digits.startsWith('1002')) return 'woori';
  if (n === 12 && /^(110|140)/.test(digits)) return 'shinhan';
  if (n === 13 && /^(301|302|312|351|352|355|356)/.test(digits)) return 'nh';
  if (groups === '6-2-6') return 'kb';
  if (groups === '3-6-5') return 'hana';
  if (groups === '3-6-2-3') return 'ibk';
  return null;
}

// 텍스트 → 계좌번호 후보 목록 (점수 높은 순)
export function extractAccounts(rawText) {
  const rawLines = rawText.split(/\r?\n/);
  const lines = rawLines.map(normalizeLine);
  // 숫자 보정은 글자 수를 바꾸지 않으므로, 은행 키워드는 보정 전 텍스트에서 찾고 위치는 그대로 쓴다
  // (보정 후에는 "KB"가 "K8"이 될 수 있음)
  const mentions = findBankMentions(rawLines.map((l) => l.replace(DASHES, '-')).join('\n'));
  const candidates = [];
  let offset = 0;

  for (const line of lines) {
    const re = /\d[\d\- ]{6,24}\d/g;
    let m;
    while ((m = re.exec(line))) {
      // 공백이 2칸 이상이면 서로 다른 숫자 묶음으로 본다
      for (const part of m[0].split(/ {2,}/)) {
        const formatted = part.trim().replace(/ ?- ?/g, '-').replace(/ /g, '-').replace(/-+/g, '-');
        const digits = formatted.replace(/\D/g, '');
        if (digits.length < 10 || digits.length > 16) continue;
        if (isPhoneNumber(digits)) continue;
        if (/^(19|20)\d{6}$/.test(digits)) continue; // 날짜

        const pos = offset + line.indexOf(part);
        let score = 0;
        if (formatted.includes('-')) score += 2;
        if (digits.length >= 11 && digits.length <= 14) score += 2;
        if (/계좌|입금|송금|예금주|ACCOUNT/i.test(line)) score += 2;

        // 가장 가까운 은행 키워드 (같은 줄 > 앞뒤 줄)
        let bank = null;
        let bankSource = null;
        let best = Infinity;
        for (const h of mentions) {
          const dist = h.end <= pos ? pos - h.end : Math.max(0, h.start - (pos + part.length));
          const penalized = dist / h.weight;
          if (dist < 120 && penalized < best) {
            best = penalized;
            bank = h.bank.id;
            bankSource = 'text';
          }
        }
        if (bank) score += 3;
        if (!bank) {
          bank = guessBankByPattern(digits, formatted);
          if (bank) bankSource = 'pattern';
        }

        candidates.push({ digits, formatted, bank, bankSource, score, line: line.trim() });
      }
    }
    offset += line.length + 1;
  }

  // 같은 번호 중복 제거 (점수 높은 것 유지)
  const byDigits = new Map();
  for (const c of candidates) {
    const prev = byDigits.get(c.digits);
    if (!prev || c.score > prev.score) byDigits.set(c.digits, c);
  }
  return [...byDigits.values()].sort((a, b) => b.score - a.score);
}

// 예금주 추정: "예금주 홍길동" / "(홍길동)" 형태
export function extractHolder(rawText) {
  const m = rawText.match(/예금주\s*[:：]?\s*([가-힣]{2,5})/) || rawText.match(/[(（]\s*([가-힣]{2,4})\s*[)）]/);
  return m ? m[1] : '';
}

export function buildTossLink({ bankToss, digits, amount }) {
  const params = [`bank=${encodeURIComponent(bankToss)}`, `accountNo=${digits}`];
  const won = Math.floor(Number(amount) || 0);
  if (won > 0) params.push(`amount=${won}`);
  return `supertoss://send?${params.join('&')}`;
}
