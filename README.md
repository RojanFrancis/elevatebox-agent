# ElevateBox AI Voice Agent 🤖📞

[![CI](https://github.com/RojanFrancis/elevatebox-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/RojanFrancis/elevatebox-agent/actions/workflows/ci.yml)

Built this for the ElevateBox SDE Intern assignment. The system calls a number, has a real sales conversation in Telugu, Hindi or English, figures out how serious the buyer is, and acts on it while still on the call — sends a WhatsApp, schedules a callback, follows up with what they actually said.

No slide deck. Just the system.

---

## What it does

A potential customer gets a call. The agent introduces itself, asks about their business, listens to what they say, and decides what to do next — all without anyone on our end doing anything.

If they're clearly interested (HOT), a WhatsApp with pricing and portfolio goes out before the call ends. If they need more time (WARM), it notes when they asked to be called back and confirms it by WhatsApp. If they're not interested (COLD), it wraps up politely and logs it.

After the call ends, a follow-up WhatsApp goes out that actually references what they said — not a copy-paste template with their name dropped in.

---

## The 8 things it does, in order

1. Dials the number on its own
2. Speaks in whatever language they answer in — Telugu, Hindi or English
3. Pitches e-commerce website development naturally
4. Asks about budget, products, timeline, features — one at a time
5. Understands what they actually said, including when they're vague
6. Classifies them HOT / WARM / COLD
7. Fires a WhatsApp mid-call if they show high intent
8. Notes a callback time if they name one and confirms it, then follows up using their words

---

## Stack

| What | How |
|------|-----|
| Voice calling | Vapi + Twilio — outbound, India routing |
| Voice | Cartesia Sindhu — Indian English, 290ms latency |
| STT | Soniox RT v5 — Indian English, 1.8% WER |
| AI conversation | GPT-4.1 — live sales conversation |
| AI brain | Gemini Flash — classification, follow-up, callback extraction |
| WhatsApp | Meta WhatsApp Cloud API — mid-call and post-call |
| Backend | Java 21, Spring Boot 3.3.4 |
| Webhook tunnel | ngrok |

---

## Project structure

```
src/main/java/com/elevatebox/agent/
├── controller/
│   └── CallController.java          # REST endpoints — trigger, webhook, classify
└── service/
    ├── VapiService.java             # Outbound call via Vapi API
    ├── GeminiService.java           # Gemini AI — classify, summarise, generate follow-up
    ├── WhatsAppService.java         # Meta WhatsApp Cloud API
    ├── LeadClassifierService.java   # Orchestrates the full pipeline
    ├── FollowUpService.java         # Post-call WhatsApp with context + resume + number
    └── CallbackService.java         # Sends the callback confirmation WhatsApp
```

---

## REST API

| Method | Endpoint | What it does |
|--------|----------|-------------|
| GET | /api/call/health | Health check |
| POST | /api/call/initiate | Triggers outbound call |
| POST | /api/call/webhook | Receives Vapi call events |
| POST | /api/call/classify | Classify a transcript — returns the label (HOT/WARM/COLD/UNKNOWN) and a one-line reason |
| POST | /api/call/mid-call | Send WhatsApp mid-call |
| POST | /api/call/process | Run full post-call pipeline on a transcript |
| GET | /api/leads | Newest analysed calls plus totals per label (needs the API key) |
| POST | /api/demo/analyze | Public: analyse a pasted transcript, nothing is sent or stored |

**Auth:** every endpoint except `/health` and the public demo route is protected. Your own calls send `X-API-Key`. `/webhook` is called by Vapi and checks a separate shared secret (`X-Vapi-Secret` or a Bearer token), so one leaked key never exposes both.

---

## How classification works

The system doesn't just look for keywords. Gemini reads the full conversation and makes a judgement call.

```
HOT  — wants it, asking price and timeline, budget confirmed
       → WhatsApp fires before call ends

WARM — real interest but something's in the way
       budget unclear, someone else decides, timing off
       → Schedule callback, capture the barrier

COLD — curious but no real intent
       → Log it, send brochure, end gracefully
```

Real people don't say "I am a HOT lead." They say things like "my brother handles this" or "how soon can you start." The prompt is built to read that.

Gemini is asked for structured JSON (`classification` plus a one-sentence `reason`), not a free-text word. If the reply is empty, cut off or unclear, the lead is labelled **UNKNOWN**, never silently COLD, so a model hiccup can't write off a real buyer. UNKNOWN leads get a neutral follow-up instead of a sales push.

### Reliability

- The webhook acknowledges Vapi immediately and does the work on a small background pool; a repeated delivery for the same call is ignored.
- One Gemini call per phone call, not four: classification, summary, WhatsApp recap and callback detection come from a single structured analysis, so the pipeline stays inside API rate limits.
- Gemini calls retry twice with backoff on 429/5xx and time out after 45 seconds. If a model keeps failing, the next one in `GEMINI_FALLBACK_MODELS` is tried.
- The follow-up WhatsApp still goes out when Gemini is down: the lead gets a generic thank-you instead of a recap.
- Every post-call step is independent: if the WhatsApp send fails, the analysis is still returned, and the response lists which steps failed.
- The mid-call WhatsApp fires at most once per call, only from the lead's own finished sentences.

---

## Demo and dashboard

- `/` is a public page where you paste a call transcript (or pick a sample) and see the lead rating, the summary and the WhatsApp follow-up the customer would receive. It can only analyse: it has no way to send a message, call anyone or write to the database. Limits per minute, per day and overall protect the Gemini quota.
- `/leads.html` lists every analysed call, newest first. It asks for the API key and keeps it in the tab only.
- Calls are stored in a database: the outcome only (rating, reason, summary, callback request, failed steps), never the transcript. Without `DB_URL` a local H2 file in `./data` is used; for TiDB Cloud or MySQL set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. A repeated end-of-call report is ignored even after a restart.

---

## Running it locally

```bash
git clone https://github.com/RojanFrancis/elevatebox-agent.git
cd elevatebox-agent
cp .env.example .env                       # fill in the values
set -a; source .env; set +a
./mvnw spring-boot:run
```

Generate the two service secrets with `openssl rand -hex 32` (once for `APP_API_KEY`, once for `VAPI_WEBHOOK_SECRET`).

Test it:

```bash
# Health check (the only open endpoint)
curl http://localhost:8081/api/call/health

# Without the key, every other endpoint returns 401
curl -i -X POST http://localhost:8081/api/call/classify

# Test classification without a real call
curl -X POST http://localhost:8081/api/call/classify \
  -H "X-API-Key: $APP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"transcript": "Yes I am interested, what is the pricing? I want to start this week."}'
# returns {"classification":"HOT","reason":"..."}
```

---

## Deploying (Render)

The repo has a `Dockerfile`, so Render builds it directly: create a **Web Service** from this repo with runtime **Docker**, set the health check path to `/api/call/health`, and add the environment variables from `.env.example`. `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` should point at a MySQL-compatible database such as TiDB Cloud. Render supplies `PORT`, which the app picks up.

Then point the Vapi assistant's Server URL at `https://<your-service>.onrender.com/api/call/webhook` (keeping the Bearer credential), so the webhook no longer depends on a local tunnel. On Render's free tier the service sleeps when idle, so the first request after a pause is slow.

---

## What works, what's next

**Works:**
- Outbound call to any Indian number via Vapi + Twilio
- Telugu, Hindi, English detection and language switching
- Lead classification — tested, consistent
- Mid-call WhatsApp on HOT intent
- Post-call follow-up using actual conversation content
- Callback requests detected from speech ("call me back tomorrow morning") and confirmed by WhatsApp; the call itself is not auto-scheduled yet
- GitHub is clean, no secrets committed

**What I'd build next:**
- Google Calendar integration for proper callback booking
- A simple dashboard showing call history, classification breakdown, conversion rate
- Prompt tuning based on real call transcripts — the first 20 calls will teach you more than any prompt engineering session

---

## Live demo

The full pipeline runs end to end — outbound call triggers from a single REST endpoint, Varshini speaks in Indian English, classifies intent mid-call, fires WhatsApp before the call ends, and schedules callbacks from natural speech.

Tested with real calls to Indian numbers via Twilio + Vapi.

---

## Built by

**Rojan Francis**
B.Tech CCE, Manipal University Jaipur (2023–2027)
Backend engineering — Java, Spring Boot, AI integrations

📱 +91 95149 71623 &nbsp;&nbsp; 🐙 [github.com/RojanFrancis](https://github.com/RojanFrancis)
