# ElevateBox AI Voice Agent 🤖📞

Built this for the ElevateBox SDE Intern assignment. The system calls a number, has a real sales conversation in Telugu, Hindi or English, figures out how serious the buyer is, and acts on it while still on the call — sends a WhatsApp, schedules a callback, follows up with what they actually said.

No slide deck. Just the system.

---

## What it does

A potential customer gets a call. The agent introduces itself, asks about their business, listens to what they say, and decides what to do next — all without anyone on our end doing anything.

If they're clearly interested (HOT), a WhatsApp with pricing and portfolio goes out before the call ends. If they need more time (WARM), it schedules a callback for whenever they said. If they're not interested (COLD), it wraps up politely and logs it.

After the call ends, a follow-up WhatsApp goes out that actually references what they said — not a copy-paste template with their name dropped in.

---

## The 8 things it does, in order

```
1. Dials the number on its own
2. Speaks in whatever language they answer in — Telugu, Hindi or English
3. Pitches e-commerce website development naturally
4. Asks about budget, products, timeline, features — one at a time
5. Understands what they actually said, including when they're vague
6. Classifies them HOT / WARM / COLD
7. Fires a WhatsApp mid-call if they show high intent
8. Schedules a callback if they name a time, follows up using their words
```

---

## Stack

| What | How |
|------|-----|
| Voice calling | Bolna AI — India routing, ElevenLabs voice, Deepgram transcription |
| AI brain | Google Gemini 3.6 Flash — classification, follow-up generation, callback extraction |
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
    └── CallbackService.java         # Extracts callback time from speech, confirms it
```

---

## REST API

| Method | Endpoint | What it does |
|--------|----------|--------------|
| GET | `/api/call/health` | Health check |
| POST | `/api/call/initiate` | Triggers outbound call |
| POST | `/api/call/webhook` | Receives Vapi/Bolna call events |
| POST | `/api/call/classify` | Classify a transcript — returns HOT/WARM/COLD |
| POST | `/api/call/mid-call` | Send WhatsApp mid-call |
| POST | `/api/call/process` | Run full post-call pipeline on a transcript |

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

---

## Running it locally

```bash
git clone https://github.com/RojanFrancis/elevatebox-agent.git
cd elevatebox-agent/agent
cp src/main/resources/application.yml.example src/main/resources/application.yml
# fill in your keys
mvn spring-boot:run
```

Test it:
```bash
# Health check
curl http://localhost:8081/api/call/health

# Test classification without a real call
curl -X POST http://localhost:8081/api/call/classify \
  -H "Content-Type: application/json" \
  -d '{"transcript": "Yes I am interested, what is the pricing? I want to start this week."}'
# returns {"classification":"HOT"}
```

---

## What works, what doesn't, what's next

**Works:**
- Outbound call to any Indian number via Bolna
- Telugu, Hindi, English detection and language switching
- Lead classification — tested, consistent
- Mid-call WhatsApp on HOT intent
- Post-call follow-up using actual conversation content
- Callback scheduling from speech ("call me back tomorrow morning")
- GitHub is clean, no secrets committed

**Doesn't work yet / rough edges:**
- Bolna webhook integration with Spring Boot backend is manual right now — the WhatsApp fires from Bolna's side, not from our backend mid-call trigger. Getting them fully wired together is the next thing.
- Callback scheduling confirms over WhatsApp but doesn't book into a calendar yet — that's a Google Calendar API integration away.
- Language switching mid-sentence (Hinglish) works reasonably but isn't perfect.

**What I'd build next:**
- Google Calendar integration for proper callback booking
- A simple dashboard showing call history, classification breakdown, conversion rate
- Prompt tuning based on real call transcripts — the first 20 calls will teach you more than any prompt engineering session

---

## Built by

**Rojan Francis**
B.Tech CCE, Manipal University Jaipur (2023–2027)
Backend engineering — Java, Spring Boot, AI integrations

📱 +91 95149 71623
🐙 github.com/RojanFrancis
