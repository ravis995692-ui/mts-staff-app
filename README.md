# MTS Staff App

Staff app for **Meridian The School, Gopalganj, Bihar**: timetable, teacher leave, automatic substitution (arrangement) planning, DPP and worksheet requests, notices and reports. Hindi and English.

**Open the app:** https://ravis995692-ui.github.io/mts-staff-app/

## How login works
1. The Admin or Coordinator adds the staff member's mobile number on the **Staff** screen.
2. The app shows a one-time 6-digit activation code. Give it to the person.
3. The person enters their mobile number and the code, and chooses their own 6-digit PIN.
4. Forgot PIN? On the Staff screen, use **New code**.

## Install on a phone
Open the link in Chrome, then tap **⋮ → Add to Home screen**.

## AI assistant
Admin, Coordinator and teachers can ask questions about school data in Hindi or English. It uses Claude through the
`ai-assistant` Supabase Edge Function (code in `supabase/functions/`). The Claude API key is stored only as the
`ANTHROPIC_API_KEY` secret in Supabase, never in this repository. Daily limit: 500 AI calls per Admin/Coordinator,
120 per teacher. Teachers' answers only use data the database lets them see.

## Technical notes
- Single-page web app (`index.html`), hosted on GitHub Pages.
- Data is stored in Supabase (PostgreSQL), protected by row-level security. Setup SQL is in `setup/`.
- The key in `index.html` is Supabase's *publishable* key, which is meant to be public. All access is controlled by the database rules.
