CREATE TABLE account (
    id            uuid         PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    full_name     varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

-- Admins are never self-registered. Password: admin12345 (BCrypt, cost 10) - change it outside coursework.
INSERT INTO account (id, email, password_hash, full_name, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin@cinema.local',
        '$2a$10$LFjgKLp4Pa3Em11PquFhG.EDAB8aMtGTY4MFxUFMVtJn2FM/YQZSm', 'Administrator', 'ADMIN');
