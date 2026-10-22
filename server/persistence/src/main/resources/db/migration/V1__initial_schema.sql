CREATE TABLE public.participants (
    id UUID NOT NULL,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    CONSTRAINT pk_participants PRIMARY KEY (id),
    CONSTRAINT ck_participants_first_name CHECK (btrim(first_name) <> ''),
    CONSTRAINT ck_participants_last_name CHECK (btrim(last_name) <> '')
);

CREATE TABLE public.users (
    id UUID NOT NULL,
    participant_id UUID NOT NULL,
    email TEXT NOT NULL,
    login TEXT COLLATE "C" NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_participant_id UNIQUE (participant_id),
    CONSTRAINT uq_users_login UNIQUE (login),
    CONSTRAINT fk_users_participant FOREIGN KEY (participant_id)
        REFERENCES public.participants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_users_email CHECK (btrim(email) <> ''),
    CONSTRAINT ck_users_login CHECK (btrim(login) <> ''),
    CONSTRAINT ck_users_password_hash CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE public.events (
    id UUID NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT ck_events_name CHECK (btrim(name) <> '')
);

CREATE TABLE public.event_participants (
    event_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT pk_event_participants PRIMARY KEY (event_id, participant_id),
    CONSTRAINT fk_event_participants_event FOREIGN KEY (event_id)
        REFERENCES public.events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_participants_participant FOREIGN KEY (participant_id)
        REFERENCES public.participants (id) ON DELETE RESTRICT
);

CREATE TABLE public.expenses (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    name TEXT NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    payer_id UUID NOT NULL,
    author_id UUID NOT NULL,
    CONSTRAINT pk_expenses PRIMARY KEY (id),
    CONSTRAINT fk_expenses_event FOREIGN KEY (event_id)
        REFERENCES public.events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_expenses_payer FOREIGN KEY (payer_id)
        REFERENCES public.participants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_expenses_author FOREIGN KEY (author_id)
        REFERENCES public.participants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_expenses_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_expenses_amount CHECK (amount_kopecks BETWEEN 1 AND 4294967295)
);

CREATE TABLE public.expense_shares (
    expense_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    CONSTRAINT pk_expense_shares PRIMARY KEY (expense_id, participant_id),
    CONSTRAINT fk_expense_shares_expense FOREIGN KEY (expense_id)
        REFERENCES public.expenses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_expense_shares_participant FOREIGN KEY (participant_id)
        REFERENCES public.participants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_expense_shares_amount CHECK (amount_kopecks BETWEEN 0 AND 4294967295)
);

CREATE INDEX idx_event_participants_participant_id ON public.event_participants (participant_id);
CREATE INDEX idx_expenses_event_id ON public.expenses (event_id);
CREATE INDEX idx_expenses_payer_id ON public.expenses (payer_id);
CREATE INDEX idx_expenses_author_id ON public.expenses (author_id);
CREATE INDEX idx_expense_shares_participant_id ON public.expense_shares (participant_id);
