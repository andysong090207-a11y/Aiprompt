// 은행 목록.
// - name: 화면 표시용 이름
// - toss: 토스 송금 딥링크(supertoss://send?bank=...)에 넣는 값
// - aliases: OCR 텍스트에서 찾을 키워드. strong=확실한 표기, weak=일상 단어와 겹칠 수 있는 표기
export const BANKS = [
  { id: 'kakao', name: '카카오뱅크', toss: '카카오뱅크', strong: ['카카오뱅크', '카카오', '카뱅', 'KAKAOBANK', 'KAKAO'], weak: [] },
  { id: 'toss', name: '토스뱅크', toss: '토스뱅크', strong: ['토스뱅크', 'TOSSBANK'], weak: ['토스', 'TOSS'] },
  { id: 'kbank', name: '케이뱅크', toss: '케이뱅크', strong: ['케이뱅크', 'K뱅크', 'KBANK'], weak: [] },
  { id: 'kb', name: 'KB국민은행', toss: '국민', strong: ['국민은행', 'KB국민', '국민', 'KB'], weak: [] },
  { id: 'shinhan', name: '신한은행', toss: '신한', strong: ['신한은행', '신한', 'SHINHAN'], weak: [] },
  { id: 'woori', name: '우리은행', toss: '우리', strong: ['우리은행', 'WOORI'], weak: ['우리'] },
  { id: 'hana', name: '하나은행', toss: '하나', strong: ['하나은행', 'KEB하나', 'KEB', 'HANA'], weak: ['하나'] },
  { id: 'nh', name: 'NH농협은행', toss: '농협', strong: ['농협은행', 'NH농협', '농협', 'NH'], weak: [] },
  { id: 'ibk', name: 'IBK기업은행', toss: '기업', strong: ['기업은행', 'IBK'], weak: ['기업'] },
  { id: 'mg', name: '새마을금고', toss: '새마을', strong: ['새마을금고', '새마을', 'MG'], weak: [] },
  { id: 'post', name: '우체국', toss: '우체국', strong: ['우체국', '우정사업'], weak: [] },
  { id: 'cu', name: '신협', toss: '신협', strong: ['신협', '신용협동'], weak: [] },
  { id: 'suhyup', name: 'Sh수협은행', toss: '수협', strong: ['수협은행', '수협'], weak: [] },
  { id: 'sc', name: 'SC제일은행', toss: 'SC제일', strong: ['SC제일', '제일은행', 'SC은행'], weak: [] },
  { id: 'citi', name: '한국씨티은행', toss: '씨티', strong: ['씨티은행', '씨티', 'CITI'], weak: [] },
  { id: 'busan', name: '부산은행', toss: '부산', strong: ['부산은행', 'BNK부산'], weak: [] },
  { id: 'kyongnam', name: '경남은행', toss: '경남', strong: ['경남은행', 'BNK경남'], weak: [] },
  { id: 'im', name: 'iM뱅크(대구)', toss: 'iM뱅크', strong: ['iM뱅크', 'IM뱅크', '대구은행', 'DGB'], weak: [] },
  { id: 'kwangju', name: '광주은행', toss: '광주', strong: ['광주은행'], weak: [] },
  { id: 'jeonbuk', name: '전북은행', toss: '전북', strong: ['전북은행'], weak: [] },
  { id: 'jeju', name: '제주은행', toss: '제주', strong: ['제주은행'], weak: [] },
  { id: 'kdb', name: 'KDB산업은행', toss: '산업', strong: ['산업은행', 'KDB'], weak: [] },
  { id: 'savings', name: '저축은행', toss: '저축은행', strong: ['저축은행'], weak: [] },
];

export function bankById(id) {
  return BANKS.find((b) => b.id === id) || null;
}
