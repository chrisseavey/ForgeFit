# ForgeFit AI Food Scanner setup

The Android app has camera/gallery capture, local image resizing, and an ANALYZE FOOD button. The scanner requires a deployed HTTPS endpoint; this repository contains the Cloudflare Worker implementation in `food-scan-worker/src/index.js`.

1. Create a Cloudflare Worker and deploy the Worker source.
2. Add `OPENAI_API_KEY` as an encrypted Worker secret (never put it in Android, GitHub, or the browser).
3. Set Worker variable `ALLOWED_ORIGIN` to `https://appassets.androidplatform.net`.
4. Before sharing the endpoint, configure Cloudflare rate limiting and OpenAI project spend limits. The sample endpoint does not authenticate users; CORS is not authentication and a public endpoint could be abused.
5. Open ForgeFit → FOOD → Add Food → SCANNER SETUP and paste your deployed HTTPS Worker URL.
6. Select or take a food photo, tap ANALYZE FOOD, review and correct estimates, then SAVE FOOD.

Photos are transmitted only when ANALYZE FOOD is tapped. The Worker calls OpenAI's Responses API with `store:false` and returns estimated meal macros. This is an estimate, not a measurement; visually hidden ingredients and exact portion weights cannot be reliably determined.

Deployment and secret configuration are not performed by committing this code. Until a Worker is deployed and configured, the scan feature cannot return AI results.
