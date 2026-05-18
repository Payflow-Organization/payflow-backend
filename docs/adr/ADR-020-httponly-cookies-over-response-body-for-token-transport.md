# ADR-020: HttpOnly cookies over response body for token transport

## Status
Accepted — Week 4 (driven by frontend integration)

## Context
JWTs issued on login and refresh must be delivered to the client and attached
to later requests. There are two mainstream approaches: return tokens in
the response body and let the client store and attach them manually, or set
them as HttpOnly cookies and let the browser handle transport automatically.

The choice affects the XSS attack surface, CSRF exposure, and how much token
management the frontend must own.

## Decision
Deliver access and refresh tokens as HttpOnly, Secure, SameSite=Strict cookies.
The frontend never touches the token values directly — the browser attaches
them automatically on every request. Spring Security CSRF protection is
disabled; SameSite=Strict serves as the CSRF mitigation instead.

## Alternatives Considered

**Response body (Bearer token in Authorization header)**
- Client receives the token in JSON, stores it in memory or localStorage
- localStorage survives page reloads but is readable by any JavaScript on the
  page — a single XSS vulnerability exposes the token directly
- In-memory storage avoids that, but the token is lost on refresh and requires
  silent re-authentication logic
- Gives the frontend full control over token lifecycle at the cost of owning
  the XSS risk entirely

**HttpOnly cookies (chosen)**
- Browser stores and attaches the cookie automatically — JavaScript cannot
  read or exfiltrate the token value regardless of XSS
- Eliminates the class of attacks where a script reads localStorage and sends
  the token to an attacker
- SameSite=Strict blocks the cookie from being sent on any cross-site request,
  replacing the need for a Spring CSRF token
- Requires the backend to set and clear cookies on login, refresh, and logout

## Rationale
PayFlow handles money. An XSS vulnerability that exposes a Bearer token from
localStorage gives an attacker a valid session with no further steps. HttpOnly
cookies remove that possibility at the transport layer — the token is never
accessible to JavaScript at all, so XSS cannot exfiltrate it.

SameSite=Strict makes CSRF impossible: the browser refuses to attach the cookie
on any request that originates from a different site, so a malicious page
cannot trigger an authenticated action. Spring Security's CSRF token mechanism
is redundant given this and is disabled.

The frontend is deployed on Vercel and the backend on Railway — different
domains. This would normally make SameSite=Strict block legitimate requests
too. It works because the Vercel frontend proxies all API calls through Vercel
itself using Next.js rewrites — Axios always targets a relative `/api/v1` URL
on the Vercel domain, and Vercel forwards the request to Railway server-side.
The browser only ever talks to the Vercel domain, so all requests are same-site
and the cookie is attached correctly.

## Consequences
- JavaScript cannot read token values — XSS cannot exfiltrate credentials
- SameSite=Strict covers CSRF — Spring CSRF token mechanism is not needed
- Login, refresh, and logout endpoints set and clear cookies rather than
  returning token strings in the response body
- The Vercel proxy is load-bearing for this security model — if the frontend
  ever makes direct requests to Railway instead of going through the proxy,
  SameSite=Strict would block the cookies and break authentication
- Any future non-browser client (mobile app, CLI) cannot use cookies and
  needs a separate token delivery mechanism
