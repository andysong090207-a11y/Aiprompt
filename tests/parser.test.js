import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractAccounts, extractHolder, buildTossLink, normalizeLine, findBankMentions, guessBankByPattern } from '../js/parser.js';

test('같은 줄의 은행명 + 하이픈 계좌번호', () => {
  const [top] = extractAccounts('국민은행 123456-78-901234 홍길동');
  assert.equal(top.digits, '12345678901234');
  assert.equal(top.bank, 'kb');
  assert.equal(top.bankSource, 'text');
});

test('은행명과 계좌가 다른 줄에 있는 메뉴판', () => {
  const text = '떡볶이 4,000\n순대 4,000\n입금계좌\n카카오뱅크\n3333-01-2345678\n예금주 김철수';
  const [top] = extractAccounts(text);
  assert.equal(top.digits, '3333012345678');
  assert.equal(top.bank, 'kakao');
  assert.equal(extractHolder(text), '김철수');
});

test('전화번호는 계좌 후보에서 제외', () => {
  const list = extractAccounts('문의 010-1234-5678\n신한 110-123-456789');
  assert.equal(list.length, 1);
  assert.equal(list[0].bank, 'shinhan');
});

test('OCR 오인식 보정 (O→0, l→1)', () => {
  assert.equal(normalizeLine('3333-O1-234567l'), '3333-01-2345671');
  const [top] = extractAccounts('농협 3O2—l234—5678—9l');
  assert.equal(top.digits, '3021234567891');
  assert.equal(top.bank, 'nh');
});

test('KBANK 안의 KB를 국민은행으로 오인하지 않음', () => {
  const m = findBankMentions('KBANK 100-123-456789');
  assert.equal(m.length, 1);
  assert.equal(m[0].bank.id, 'kbank');
});

test('KB 바로 뒤에 숫자가 붙어도 국민은행 인식', () => {
  const [top] = extractAccounts('KB 123456-78-901234');
  assert.equal(top.bank, 'kb');
});

test('키워드가 없으면 형식으로 추정', () => {
  assert.equal(guessBankByPattern('3333012345678'), 'kakao');
  assert.equal(guessBankByPattern('100012345678', '1000-1234-5678'), 'toss');
  assert.equal(guessBankByPattern('100123456789', '100-123-456789'), 'kbank');
  const [top] = extractAccounts('1002-123-456789');
  assert.equal(top.bank, 'woori');
  assert.equal(top.bankSource, 'pattern');
});

test('여러 후보 중 계좌 키워드 있는 줄 우선', () => {
  const list = extractAccounts('사업자 1234567890\n계좌 우리은행 1002-345-678901');
  assert.equal(list[0].digits, '1002345678901');
});

test('토스 딥링크 생성', () => {
  assert.equal(
    buildTossLink({ bankToss: '국민', digits: '12345678901234', amount: '8000.7' }),
    'supertoss://send?bank=%EA%B5%AD%EB%AF%BC&accountNo=12345678901234&amount=8000',
  );
  assert.equal(buildTossLink({ bankToss: '신한', digits: '110123456789', amount: '' }), 'supertoss://send?bank=%EC%8B%A0%ED%95%9C&accountNo=110123456789');
});
