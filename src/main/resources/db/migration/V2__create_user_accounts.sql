CREATE TABLE identity_service.user_accounts(
 id UUID PRIMARY KEY,
  fullname VARCHAR(100) NOT NULL,
  email VARCHAR(254) NOT NULL,
  password_hash TEXT NOT NULL,
  CONSTRAINT uq_user_accounts_email UNIQUE(email),
  CONSTRAINT ck_user_accounts_fullname_nonblank CHECK (char_length(btrim(fullname))>0),
  CONSTRAINT ck_user_accounts_email_canonical CHECK (email = lower(email) AND email = btrim(email) AND char_length(email) >0),
  CONSTRAINT ck_user_accounts_password_hash_nonblank CHECK (char_length(btrim(password_hash)) > 0)
);
