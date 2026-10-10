// 앱 화면 파일을 캐시해서 오프라인/느린 망에서도 바로 열리게 한다.
// (OCR 언어 데이터는 Tesseract.js가 IndexedDB에 따로 캐시함)
const CACHE = 'account-snap-v1';
const SHELL = ['./', 'index.html', 'css/style.css', 'js/app.js', 'js/parser.js', 'js/banks.js', 'manifest.webmanifest', 'icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const cacheable = url.origin === location.origin || url.hostname === 'cdn.jsdelivr.net';
  if (!cacheable) return;
  // 네트워크 우선, 실패하면 캐시
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
        }
        return res;
      })
      .catch(() => caches.match(req).then((r) => r || Promise.reject(new Error('offline')))),
  );
});
