-- =====================================================================
--  MTS Staff App — database setup (Supabase / PostgreSQL)
--  Meridian The School, Gopalganj, Bihar
--  Run once in Supabase → SQL Editor. Safe to re-run (drops and recreates).
-- =====================================================================

create extension if not exists pgcrypto with schema extensions;

-- ---------- data tables: every record is one row, its fields in "data" ----------
do $$
declare t text;
begin
  foreach t in array array['staff','timetable','settings','absences','leaves','plans','requests','notices','notifications','audit','acks'] loop
    execute format('drop table if exists public.%I cascade', t);
    execute format('create table public.%I (
      id text primary key,
      data jsonb not null,
      owner text,
      updated_at timestamptz not null default now()
    )', t);
    execute format('alter table public.%I enable row level security', t);
  end loop;
end $$;

-- ---------- private login tables (never readable through the API) ----------
drop table if exists public.staff_secrets cascade;
drop table if exists public.sessions cascade;
create table public.staff_secrets (
  staff_id text primary key references public.staff(id) on delete cascade,
  code text,                 -- one-time activation code from the office
  pin_hash text,             -- bcrypt hash of the teacher's 6-digit PIN
  failed int not null default 0,
  locked_until timestamptz
);
create table public.sessions (
  uid uuid primary key,      -- the browser's Supabase user id
  staff_id text not null references public.staff(id) on delete cascade,
  created_at timestamptz not null default now()
);
alter table public.staff_secrets enable row level security;
alter table public.sessions enable row level security;

-- ---------- who is signed in ----------
create or replace function public.my_staff_id() returns text
language sql stable security definer set search_path = public as $$
  select s.staff_id from sessions s join staff st on st.id = s.staff_id
  where s.uid = auth.uid() and coalesce((st.data->>'active')::boolean, false)
  limit 1
$$;

create or replace function public.is_manager() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from staff where id = public.my_staff_id() and data->>'role' in ('admin','coordinator'))
$$;

-- ---------- keep owner + updated_at correct, whatever the browser sends ----------
create or replace function public.set_row_meta() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  new.owner := case tg_table_name
    when 'leaves' then new.data->>'teacherId'
    when 'requests' then new.data->>'teacherId'
    when 'notifications' then new.data->>'userId'
    when 'acks' then new.data->>'teacherId'
    when 'audit' then new.data->>'by'
    else null end;
  return new;
end $$;
do $$
declare t text;
begin
  foreach t in array array['staff','timetable','settings','absences','leaves','plans','requests','notices','notifications','audit','acks'] loop
    execute format('create trigger row_meta before insert or update on public.%I for each row execute function public.set_row_meta()', t);
  end loop;
end $$;

-- every new staff member gets a 6-digit activation code
create or replace function public.new_staff_secret() returns trigger
language plpgsql security definer set search_path = public, extensions as $$
begin
  insert into staff_secrets(staff_id, code)
  values (new.id, lpad((floor(random() * 1000000))::int::text, 6, '0'))
  on conflict (staff_id) do nothing;
  return new;
end $$;
create trigger staff_secret after insert on public.staff for each row execute function public.new_staff_secret();

-- ---------- row level security ----------
-- read: any signed-in active staff member (notifications: only your own; audit: managers)
do $$
declare t text;
begin
  foreach t in array array['staff','timetable','settings','absences','leaves','plans','requests','notices','acks'] loop
    execute format('create policy read_all on public.%I for select using (public.my_staff_id() is not null)', t);
  end loop;
  -- tables only Admin / Coordinator may change
  foreach t in array array['staff','timetable','settings','absences','plans','notices'] loop
    execute format('create policy mgr_insert on public.%I for insert with check (public.is_manager())', t);
    execute format('create policy mgr_update on public.%I for update using (public.is_manager()) with check (public.is_manager())', t);
    execute format('create policy mgr_delete on public.%I for delete using (public.is_manager())', t);
  end loop;
end $$;

-- leave: teachers apply for themselves; only managers decide; teacher may withdraw while pending
create policy own_insert on public.leaves for insert with check (
  public.is_manager() or (owner = public.my_staff_id() and data->>'status' = 'pending'));
create policy mgr_update on public.leaves for update using (public.is_manager()) with check (public.is_manager());
create policy del on public.leaves for delete using (
  public.is_manager() or (owner = public.my_staff_id() and data->>'status' = 'pending'));

-- DPP / worksheet requests: teachers raise their own; managers run the workflow
create policy own_insert on public.requests for insert with check (
  public.is_manager() or (owner = public.my_staff_id() and data->>'status' = 'requested'));
create policy upd on public.requests for update
  using (public.is_manager() or owner = public.my_staff_id() or data->>'assignee' = public.my_staff_id())
  with check (public.is_manager() or owner = public.my_staff_id() or data->>'assignee' = public.my_staff_id());
create policy del on public.requests for delete using (
  public.is_manager() or (owner = public.my_staff_id() and data->>'status' = 'requested'));

-- notifications: anyone signed in can notify; you read and clear only your own
create policy own_read on public.notifications for select using (owner = public.my_staff_id());
create policy any_insert on public.notifications for insert with check (public.my_staff_id() is not null);
create policy own_update on public.notifications for update using (owner = public.my_staff_id()) with check (owner = public.my_staff_id());
create policy own_delete on public.notifications for delete using (owner = public.my_staff_id());

-- audit log: everyone writes their own entries; only managers read
create policy mgr_read on public.audit for select using (public.is_manager());
create policy own_insert on public.audit for insert with check (owner = public.my_staff_id());

-- "Seen" ticks on substitution periods: each teacher marks their own
create policy own_insert on public.acks for insert with check (owner = public.my_staff_id());
create policy own_update on public.acks for update using (owner = public.my_staff_id()) with check (owner = public.my_staff_id());

-- ---------- login functions (called from the app) ----------
create or replace function public.staff_status(p_phone text) returns text
language plpgsql stable security definer set search_path = public as $$
declare st record; sec record;
begin
  select * into st from staff where data->>'phone' = p_phone limit 1;
  if not found then return 'unregistered'; end if;
  if not coalesce((st.data->>'active')::boolean, false) then return 'inactive'; end if;
  select * into sec from staff_secrets where staff_id = st.id;
  if sec.locked_until is not null and sec.locked_until > now() then return 'locked'; end if;
  if sec.pin_hash is null then return 'needs_activation'; end if;
  return 'ready';
end $$;

create or replace function public.activate(p_phone text, p_code text, p_pin text) returns text
language plpgsql security definer set search_path = public, extensions as $$
declare st record; sec record;
begin
  if auth.uid() is null then return 'ERR:not_signed_in'; end if;
  if p_pin !~ '^[0-9]{6}$' then return 'ERR:bad_pin_format'; end if;
  select * into st from staff where data->>'phone' = p_phone and coalesce((data->>'active')::boolean, false) limit 1;
  if not found then return 'ERR:unregistered'; end if;
  select * into sec from staff_secrets where staff_id = st.id for update;
  if sec.locked_until is not null and sec.locked_until > now() then return 'ERR:locked'; end if;
  if sec.pin_hash is not null then return 'ERR:already_active'; end if;
  if sec.code is null or sec.code <> p_code then
    update staff_secrets set failed = failed + 1,
      locked_until = case when failed + 1 >= 5 then now() + interval '15 minutes' else null end
    where staff_id = st.id;
    return 'ERR:bad_code';
  end if;
  update staff_secrets set pin_hash = crypt(p_pin, gen_salt('bf')), code = null, failed = 0, locked_until = null where staff_id = st.id;
  insert into sessions(uid, staff_id) values (auth.uid(), st.id)
    on conflict (uid) do update set staff_id = excluded.staff_id, created_at = now();
  return st.id;
end $$;

create or replace function public.login(p_phone text, p_pin text) returns text
language plpgsql security definer set search_path = public, extensions as $$
declare st record; sec record;
begin
  if auth.uid() is null then return 'ERR:not_signed_in'; end if;
  select * into st from staff where data->>'phone' = p_phone and coalesce((data->>'active')::boolean, false) limit 1;
  if not found then return 'ERR:unregistered'; end if;
  select * into sec from staff_secrets where staff_id = st.id for update;
  if sec.locked_until is not null and sec.locked_until > now() then return 'ERR:locked'; end if;
  if sec.pin_hash is null then return 'ERR:needs_activation'; end if;
  if crypt(p_pin, sec.pin_hash) <> sec.pin_hash then
    update staff_secrets set failed = failed + 1,
      locked_until = case when failed + 1 >= 5 then now() + interval '15 minutes' else null end
    where staff_id = st.id;
    return 'ERR:bad_pin';
  end if;
  update staff_secrets set failed = 0, locked_until = null where staff_id = st.id;
  insert into sessions(uid, staff_id) values (auth.uid(), st.id)
    on conflict (uid) do update set staff_id = excluded.staff_id, created_at = now();
  return st.id;
end $$;

create or replace function public.logout() returns void
language sql security definer set search_path = public as $$
  delete from sessions where uid = auth.uid();
$$;

-- Admin / Coordinator: give a staff member a new activation code (forgot PIN, new phone)
create or replace function public.reset_pin(p_staff_id text) returns text
language plpgsql security definer set search_path = public, extensions as $$
declare new_code text := lpad((floor(random() * 1000000))::int::text, 6, '0');
begin
  if not public.is_manager() then raise exception 'not_allowed'; end if;
  insert into staff_secrets(staff_id, code) values (p_staff_id, new_code)
    on conflict (staff_id) do update set code = new_code, pin_hash = null, failed = 0, locked_until = null;
  delete from sessions where staff_id = p_staff_id and uid <> auth.uid();
  return new_code;
end $$;

-- Admin / Coordinator: see who still needs to activate, and their codes
create or replace function public.staff_codes() returns table(staff_id text, code text, activated boolean)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_manager() then raise exception 'not_allowed'; end if;
  return query select s.staff_id, s.code, s.pin_hash is not null from staff_secrets s;
end $$;

revoke all on function public.reset_pin(text) from anon;
revoke all on function public.staff_codes() from anon;

-- ---------- live updates ----------
do $$
declare t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
  foreach t in array array['staff','timetable','settings','absences','leaves','plans','requests','notices','notifications','acks'] loop
    begin
      execute format('alter publication supabase_realtime add table public.%I', t);
    exception when duplicate_object then null;
    end;
  end loop;
end $$;
