// staff alerts on phones (android chrome shows notifications only through a service worker). it only shows the
// alerts the open dashboard page sends and brings the page to the front on a tap. no caching, no push
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => (list.length ? list[0].focus() : self.clients.openWindow("/"))),
  );
});
