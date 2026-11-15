/* MTS Staff App — keeps the app's own files on the phone so it opens instantly, even on slow internet.
   Opens from the saved copy at once and quietly fetches the newest version for next time.
   School data is never cached here: it always comes from the database. */
const CACHE = 'mts-shell-v3';
// only the small files here; the page itself saves the two big ones (app.js.gz, supabase.js.gz) right after downloading them
const SHELL = ['./', 'manifest.webmanifest', '../icon-192.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => Promise.allSettled(SHELL.map(u => c.add(u)))).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  const isPage = req.mode === 'navigate';
  const key = isPage ? new URL('./', self.registration.scope).href : url.origin + url.pathname; // one saved copy per file
  e.respondWith(caches.open(CACHE).then(async cache => {
    const hit = await cache.match(key, { ignoreSearch: true });
    const fresh = fetch(req, { cache: 'no-cache' }).then(res => { if (res && res.ok) cache.put(key, res.clone()); return res; });
    if (hit) { e.waitUntil(fresh.catch(() => { })); return hit; }
    return fresh.catch(() => cache.match(new URL('./', self.registration.scope).href));
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => { for (const c of list) { if ('focus' in c) return c.focus(); } return self.clients.openWindow('./'); }));
});
