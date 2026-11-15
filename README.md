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

## Timetable Builder
Admin and Coordinator set, for each class, how many periods each subject gets and who teaches it. The app then builds a
clash-free weekly timetable (for all classes, or one class while keeping the others), shows a preview, and applies it
only when confirmed. Classes and subjects can be added.

## Smart Assistant
A built-in assistant answers questions about school data in English, Hindi and Hinglish (absences, free teachers,
schedules, substitutions, leave, DPP/worksheet requests, workload, what-if cover planning, timetable checks).
It runs entirely inside the app: no outside AI service is used and nothing leaves the school's database.
The `supabase/functions/ai-assistant` function is from an earlier version and is no longer called by the app.

## Technical notes
- Single-page web app (`index.html`), hosted on GitHub Pages.
- Data is stored in Supabase (PostgreSQL), protected by row-level security. Setup SQL is in `setup/`.
- The key in `index.html` is Supabase's *publishable* key, which is meant to be public. All access is controlled by the database rules.
