# The control panel

Once OrbixERP is on a server, the same window you installed it with becomes its **control panel**.
Double-click `Remote-Setup.cmd`, enter the server details, and the wizard notices it is already
installed and offers **Open control panel**.

Everything here happens on the server. You never type a command on it, and nothing is installed on
your PC.

To install on a server for the first time, see [REMOTE-INSTALL](REMOTE-INSTALL.md).

---

## What each button does

| Button | What happens on the server | Safe while people are working? |
|---|---|---|
| **Is it running?** | Reports whether each part is up, healthy, and on which address | Yes — reads only |
| **Recent activity** | The last 200 lines of the application log | Yes — reads only |
| **Back up now** | Writes a database backup into `backups/` on the server | Yes — runs against the live database |
| **Restart** | Stops and starts again | **No** — a short outage, seconds |
| **Start the system** | Starts it and waits until it genuinely answers | Yes |
| **Stop the system** | Shuts down cleanly. **Your data is kept.** | **No** — nobody can use it until started again |
| **Settings (.env)** | Opens the server's settings file to read or change | Reading yes; saving needs a restart |
| **Restore a backup** | Replaces the database with a backup file from your PC | **No** — and it cannot be undone |
| **Update to this bundle** | Upgrades the server to the version in this folder | **No** — takes a backup first, then a short outage |
| **Open in browser** | Opens the ERP address on this PC | Yes |

Each button runs the matching command in the installation folder — `./orbixerp.sh status`,
`logs`, `backup`, `restart`, `start`, `stop`, `update`, `restore`. Nothing is done by a separate
mechanism, so a person at the server's own terminal sees exactly the same behaviour.

While a job is running every button is disabled, and the heading shows a clock so you can tell the
difference between *slow* and *stuck*. A database backup on a real system is not quick.

---

## Settings (.env)

Opens the server's `.env` — the file holding every setting, including its passwords. It is read
and written straight down the encrypted connection; no copy is left on your PC.

**Changes take effect only after a restart.** The panel offers one as soon as you save.

Before writing, the previous file is kept on the server as `.env.bak-<date>-<time>`, so a mistyped
line can always be put back.

### Four lines cannot be changed here

`ERP_DB_PASSWORD`, `ERP_DB_MODE`, `ERP_DB_NAME` and `ERP_DB_USER` are shown but refused if edited.

This is not caution for its own sake. **Changing the database password here does not change the
password on the database** — the database already exists and keeps the password it was created
with. All you would achieve is stopping OrbixERP being able to open its own data, and the way back
is not obvious to somebody who has just locked themselves out. The same is true of the database
name, its user, and whether it runs in a container or on the host.

If one of those genuinely has to change, it is a job to do deliberately, with a backup in hand and
the database changed to match. Ask us.

### What is worth changing

| Setting | Why you might | Watch out for |
|---|---|---|
| `ERP_PUBLIC_HOST` | The address people use has changed | With a certificate, it must match the real name or the certificate stops matching |
| `ERP_HTTP_PORT` | Something else already uses that port | Anyone using the old port has to be told |
| `ERP_BIND_ADDR` | Restrict which network can reach it | Bind too narrowly and nobody reaches it at all |
| `ERP_TLS_ENABLED` | Turn a proper certificate on or off | Needs a real domain name pointed at the server |

---

## Backups

**Back up now** writes into `backups/` in the installation folder on the server. It runs against
the live database, so it is safe during the working day.

A backup that only exists on the same server is not really a backup. Copy it somewhere else —
another machine, or storage that is not that server. If the server is lost, anything on it is lost
with it.

---

## Updating

**Update to this bundle** upgrades the server to the version in the folder you launched from. So
to update, get a newer bundle from us, unpack it, and run its `Remote-Setup.cmd`.

The update takes a database backup **first** and stops if that backup fails. Your data, settings
and security keys are kept.

If the server is already on that version, the panel says so and does nothing.

> Database changes only run forwards. Restoring the backup the update names is the only way back
> to the previous version, which is why the update refuses to proceed without one.

---

## Restoring a backup

**Restore a backup** replaces this server's database with a backup file from your PC. It is how
you move an existing system onto a new server: install here, then restore the other system's
backup over the top.

It runs in three steps, and says which one it is on:

1. **A backup of the current database is taken first.** If that fails, nothing is restored.
   This is your way back if the wrong file was chosen.
2. The file is uploaded to `backups/` on the server, with a progress percentage.
3. The restore runs.

Before any of that you must **type `RESTORE`** — the button stays disabled until the word is
exactly right. That is deliberate: this is the only action here that cannot be undone.

> **Everything recorded on this server since that backup was taken is lost.** Not archived, not
> merged — replaced.

### Two things that follow a restore

**Sign in with the accounts from the system the backup came from.** The users on this server were
replaced along with the rest of the database, so the administrator password this server was
installed with no longer exists.

**If nobody can sign in at all, the `secrets/` folder was not copied across.** It holds the
sign-in keys, and they do not travel inside a database backup. The data is intact; the system
simply cannot validate anyone. Copy `secrets/` from the other system into the installation folder
and restart.

### Moving a system to a new server

1. On the old server: **Stop the system**, then **Back up now**
2. Copy the backup file **and the `secrets/` folder** off that server
3. Install on the new server, put `secrets/` in place, then **Restore a backup**
4. Sign in with the old system's accounts and check data you recognise

Keep the old server switched off but intact for a week. Database changes only run forwards, so
that machine is the only way back.

---

## When something goes wrong

| What you see | What it means | What to do |
|---|---|---|
| *That did not work* with messages above | The command failed on the server | Read the messages; [TROUBLESHOOTING](TROUBLESHOOTING.md) covers the usual causes |
| The clock keeps counting on a backup | Normal on a large database | Leave it; backups take as long as they take |
| *Is it running?* says unhealthy | Started but not answering yet, or failing to start | Wait a minute and check again, then **Recent activity** |
| Nothing responds after a settings change | A setting is wrong | Re-open **Settings**, compare with `.env.bak-…`, put the line back, restart |
| *The server's identity has changed* | The server's fingerprint no longer matches | Stop. Find out whether it was rebuilt before continuing |

---

## What this panel will not do

It will not remove the installation, drop a database, or delete your backups. There is no button
for any of that, on purpose.

**Restore** is the single exception to the rule that nothing here is irreversible, and it earns
its place: restoring is how a system is moved to a new server, and a cutover is exactly the wrong
moment to be typing commands by hand under time pressure. So it is offered — but with the two
things a hand-typed restore usually lacks: a backup of what is about to be replaced, taken
automatically first, and a confirmation you have to type out.
