/*
 * MoSes service worker. It exists so phones can install MoSes as a web app; it deliberately caches
 * nothing - monitoring data must always be live, and a stale cached page or API answer would be
 * misleading. Requests go straight to the network as if there were no service worker.
 */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
