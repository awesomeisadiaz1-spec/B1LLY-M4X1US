// Minimal service worker so the page installs as an app. API calls are never cached.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET' || new URL(e.request.url).pathname.startsWith('/api/')) return;
  e.respondWith(fetch(e.request).then(r => { const c = r.clone(); caches.open('b1').then(k => k.put(e.request, c)); return r; })
    .catch(() => caches.match(e.request)));
});
