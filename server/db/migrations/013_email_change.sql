-- Changing the email address from the staff website: a code goes to the NEW address and the change applies only once
-- it is confirmed. The pending address lives on the code row itself, so a newer request voids the older one.
alter table email_codes drop constraint email_codes_purpose_check;
alter table email_codes
  add constraint email_codes_purpose_check check (purpose in ('verify', 'reset', 'email_change'));

alter table email_codes add column new_email text;
alter table email_codes
  add constraint email_codes_new_email_check check ((purpose = 'email_change') = (new_email is not null));
