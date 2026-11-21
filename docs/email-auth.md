# Email OTP authentication

Google authentication remains supported. Email authentication uses a 6-digit code for each login, not a reusable password. New users choose a name and username after mailbox verification. Existing email owners receive the same SALOM profile/session. Google may attach to an email-only profile only when `email_identities` records OTP enrollment; legacy unverified email rows cannot be silently linked.

Server-only configuration in `/etc/salom-api/environment`:

- `BREVO_API_KEY`: transactional email API credential.
- `BREVO_SENDER_EMAIL`: sender validated in Brevo.
- `SALOM_OTP_PEPPER`: at least 32 random characters, independent of the API credential.

The environment file is loaded by the existing systemd service. Keep it root owned and readable only by the service group. Never distribute these values to clients or Git. Brevo must permit the VPS outbound IPv4/IPv6 and the sender must be verified. API acceptance (201) does not prove delivery; check Brevo event logs.

Routes:

- `POST /auth/email/code` with email: 300-second HMAC digest in Redis; 60-second resend lock; per-IP request limits.
- `POST /auth/email/verify` with email and code: Redis Lua atomically counts attempts, blocks after five failures and consumes a valid code. Existing accounts receive a normal revocable SALOM session. New addresses receive a 300-second registration ticket.
- `POST /auth/email/register` with email, ticket, name, username: consumes the ticket and creates a profile or returns the existing email owner. Username conflicts can be corrected while the registration ticket is valid.

Tests include hash/TTL storage, cooldown, invalid/expired/locked challenges, single-use registration and linking in both directions. The live Redis concurrency probe uses isolated random QA keys and cleans them afterwards. Physical Android/Windows user flows still require a mailbox/user test before claiming full end-to-end validation.
