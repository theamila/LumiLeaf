self.addEventListener('install', () => {
    self.skipWaiting();
});
self.addEventListener('activate', () => {
    self.clients.claim();
});
self.addEventListener('fetch', () => {
    // Always go to network, no offline caching.
});