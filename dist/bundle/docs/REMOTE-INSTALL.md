# Installing OrbixERP on a server, from Windows

This guide is for putting OrbixERP on a **server somewhere else** — in your server room, or
with a hosting provider such as AWS — using a wizard that runs on your own Windows PC.

You never type a command on the server. The wizard signs in to it for you, sends everything
across, installs it, and afterwards lets you check it, back it up, restart it and update it
from the same window.

If OrbixERP is going to run **on the Windows PC in front of you**, you want
[INSTALL.md](INSTALL.md) instead. This guide takes about 30 minutes, most of which is the
upload.

---

## 1. What you need first

**The server.** A Linux machine with a fixed address that stays switched on.

| | Minimum | Comfortable |
|---|---|---|
| Memory (RAM) | 4 GB | 8 GB |
| Free disk space | 20 GB | 50 GB+ |
| Operating system | Ubuntu, Debian, Amazon Linux, Red Hat, Rocky or AlmaLinux | — |
| Processor | must match your bundle — `amd64` for almost everything, `arm64` for AWS Graviton | — |

Docker does **not** need to be installed beforehand — the wizard installs it if it is
missing. Everything else is uploaded from your PC, so the server needs no internet
connection at all, with one exception noted in section 6.

**Its address.** The name or IP address you connect to, e.g. `16.170.11.41` or
`erp.mycompany.co.tz`.

**A user name to sign in as.** This depends on the server, and it is not usually `root`:

| Server | User name |
|---|---|
| Ubuntu | `ubuntu` |
| Amazon Linux | `ec2-user` |
| Debian | `admin` or `debian` |
| Red Hat / Rocky / Alma | `ec2-user`, `rocky`, `almalinux` or your own account |

**A key file.** A file ending in `.pem` — the one your hosting provider gave you when the
server was created. AWS offers it once, at creation time, and never again.

> **Why not a password?** The wizard uses the SSH client built into Windows, and that client
> takes a password only from a person typing at a keyboard — there is no way to hand it one
> from a program. Tools that appear to do it bundle an extra piece of software to work
> around it. We would rather not put a password-handling helper on your machine, so the
> wizard supports key files only. If you have no key file, ask whoever set the server up to
> add one, or create the server again and keep the key this time.
>
> A key with a **passphrase** cannot be used either, for the same reason. The wizard tells
> you if you pick one.

**SSH turned on in Windows.** It is on by default in Windows 10 and 11. If it has been
turned off, the wizard says so and tells you where to switch it back on — nothing needs
downloading.

**Ports open.** The server's firewall — and, on a cloud server, its *security group* — must
allow:

- port **22**, from your PC, so the wizard can connect at all;
- the port your staff will use: **8080** by default, or **80 and 443** if you choose HTTPS.

This is set in your hosting provider's control panel, not by the wizard.

---

## 2. Start it

Unzip the bundle anywhere on your PC — the Downloads folder is fine — and
**double-click `Remote-Setup.cmd`**.

> Windows may show *"Windows protected your PC"* or a *Security Warning*, because the file
> arrived from outside this computer. Click **More info → Run anyway**, or **Run**. This is
> normal for any downloaded installer.

---

## 3. What each screen asks

### Welcome — checks on this computer

Confirms the Windows SSH client is available and that the bundle is complete, and shows how
much has to be uploaded. Nothing has been sent anywhere yet.

### Your server

| Field | What to enter |
|---|---|
| Server address | The name or IP address of the server |
| Port | `22` unless somebody changed it |
| Sign in as | The user name from the table in section 1 |
| Key file | **Browse** to your `.pem` file |
| Install into | `/opt/orbixerp` unless you have a reason to choose elsewhere |

**About the install folder.** OrbixERP goes into a folder of its own, so no root rights are
needed to run it, back it up or update it afterwards, and everything — settings, security
keys and backups — stays together in one place. System directories such as `/etc` and `/usr`
are refused, and so are `/tmp` and `/var/tmp`, which the server empties when it restarts.

**"Other Windows accounts can read this key file."** Common, and harmless to fix: a `.pem`
downloaded from a browser inherits the permissions of the folder it landed in, and SSH
refuses to use a key like that. Say **Yes** and Windows locks it to your account. Nothing
else about the file changes.

### Server checks

The wizard connects and asks the server about itself. Nothing is changed by any of it.

**The first time you connect, it shows you a fingerprint and asks whether it is your
server.** This is the one question in the wizard that is worth stopping for.

SSH has no way of knowing that the machine answering is really yours. All it can do is show
you the fingerprint of the key that replied. If somebody is sitting between you and your
server, this is the moment they are caught — and only if you compare that fingerprint
against a copy you obtained another way:

- **On AWS** — the fingerprints are printed in the instance's system log the first time it
  boots. In the console: select the instance, then **Actions → Monitor and troubleshoot →
  Get system log**.
- **On a server you can already reach** — run
  `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on it.

Answer **Yes** only if it matches. Answering Yes to a fingerprint you have not checked means
trusting whatever machine replied, which may not be yours. You are asked once per server;
after that the wizard recognises it silently.

If instead you are told the identity has **CHANGED**, nothing is sent and the wizard stops.
That means either the server was rebuilt, or the address now points at a different machine —
or something is intercepting the connection. See section 7.

The checks then cover:

| Check | What a failure means |
|---|---|
| Signing in | Wrong user name, wrong key file, or port 22 not open to your PC |
| Operating system | The wizard installs onto Linux servers |
| Processor type | **The bundle does not match the server.** Ask your supplier for the right one — see section 7 |
| Free disk space | Not enough room for the upload, the software and the database |
| Docker | Already there, or the wizard will install it — unless the account cannot use `sudo` |
| Install folder | The folder can be created |
| OrbixERP | Whether something is already installed there |

**If OrbixERP is already installed**, the wizard offers two choices instead of reinstalling
over the top: **Manage it** (section 5) or **Update it to this bundle** (section 6). There is
no option that reinstalls over a working system, because that would give the database a new
password it has never heard of and log every user out.

### Your organisation

Your organisation, company and main branch names, the timezone and the currency the accounts
are kept in. Used once, to create your company and the first administrator.

> **The currency is difficult to change later.** Take a moment over it.

### Access — how people reach the system

Three choices:

| Choice | Use it when | What your staff see |
|---|---|---|
| **Plain web address (http)** | The system is used inside your own office or over a private network | `http://your-server:8080` |
| **Encrypted, certificate the server makes itself** | You want traffic encrypted but have no domain name | `https://your-server`, with a browser warning to click through on each new device |
| **Encrypted, free trusted certificate** | You have a real domain name pointing at this server, and ports 80 and 443 are open to the internet | `https://erp.yourcompany.co.tz`, no warnings |

The address you type here is the one **your staff** will type — which is not always the
address you connect to the server on. Choosing either encrypted option also closes the plain
port to the outside, so nobody can quietly use the unencrypted one instead.

The trusted certificate is issued and renewed automatically, but only for a **domain name** —
never for an IP address. The wizard says so if you try.

### Review

Everything you have chosen, on one page. **Nothing has been changed on the server yet.**

### Working

In order: the folder is prepared, Docker is installed if it is missing, the bundle is
uploaded (with a progress bar, a transfer rate and an estimate), the settings are written,
and then the server runs the same installer a Linux administrator would have run by hand.

Leave the window open. The first start creates the database structure and takes several
minutes on its own; a clock keeps counting so you can see it is still working.

Each uploaded file is checked against what was sent, so a dropped connection is caught
straight away rather than surfacing later as a damaged file.

### Finished

Your web address, the user name `rootadmin`, and the administrator password — with a
**Copy password** button.

**Do these three things now:**

1. **Write the password down**, or copy it somewhere safe. It is also in the `.env` file on
   the server, readable only by the account you signed in as.
2. **Open the address and sign in**, then change that password from inside the application.
3. **Take a backup** — section 5. Do it on day one, not on the day you need it.

---

## 4. Where things end up on the server

Everything is in the install folder — `/opt/orbixerp` unless you chose otherwise:

```
.env              your settings and passwords
secrets/          the sign-in security keys
backups/          database backups
docs/             these guides
orbixerp.sh       the command-line control script, if you ever want it
```

**Back up `backups/`, `.env` and `secrets/` together, off the server.** A database backup on
its own cannot be signed into, and a backup that only exists on the server does not protect
you from that server failing.

---

## 5. The control panel

Run `Remote-Setup.cmd` again, enter the same server details, and the wizard finds the
existing installation and offers **Open control panel**:

| Button | What it does |
|---|---|
| **Is it running?** | Containers, health and the installed version |
| **Recent activity** | The last 200 lines of the application log |
| **Back up now** | Writes a database backup into `backups/` on the server |
| **Restart** | Stops and starts it — this is how settings changes take effect |
| **Start the system** | Starts it again after it has been stopped |
| **Stop the system** | Shuts it down. Your data is kept; nobody can use it until it is started again |
| **Settings (.env)** | Read or change the server's settings, including how it is reached |
| **Update to this bundle** | Section 6 |
| **Open in browser** | Opens the address your staff use |

**[REMOTE-CONTROL-PANEL.md](REMOTE-CONTROL-PANEL.md) is the full reference** — what each button
runs on the server, which are safe to use while people are working, and which four settings cannot
be changed from the panel because doing so would lock the system out of its own database.

Restoring a backup is deliberately **not** offered here. It replaces the database and
everything recorded since that backup was taken, which is not a thing to have a button for.
[OPERATIONS.md](OPERATIONS.md) has the command.

---

## 6. Updating to a newer version

Get the new bundle, unzip it, and run **its** `Remote-Setup.cmd`. Enter the same server
details. The wizard sees the older version and offers to update it.

What happens, in order:

1. The new bundle is uploaded to a temporary folder on the server.
2. **A database backup is taken first.** If that backup fails for any reason, the update
   stops and nothing changes.
3. The new software is loaded, the settings files are refreshed and the system restarts.
4. The temporary folder is removed.

Your data, your settings, your security keys and your existing backups are all kept.

> **Going back is not a matter of reinstalling the old version.** Database changes only run
> forwards, so the old version will not start against the upgraded database. The backup taken
> in step 2 is the only way back — its filename is shown when the update finishes. Keep it
> until you are satisfied the new version is behaving.

**The one step that needs the server to reach the internet** is installing Docker, and only
on a server that does not already have it. Everything else — the application, the database
engine, the web server — is uploaded from your PC. If your server has no internet access,
install Docker on it from your own package mirror first, and the wizard will use it.

---

## 7. When something goes wrong

The wizard stops at the first failure and says what failed. Nothing is left half-done, and
it is always safe to fix the problem and run it again.

### "The server refused this key"

Almost always the **user name**, not the key. Check the table in section 1: an Ubuntu server
does not accept `root`, and an Amazon Linux server does not accept `ubuntu`. Then check the
`.pem` really is the one issued for **this** server.

### "No answer on that address and port"

Port 22 is not open to your PC, the address is wrong, or the server is switched off. On a
cloud server, check the security group allows SSH **from your current internet address** —
which changes if your office connection is not fixed.

### "This bundle is for amd64 servers; this one is arm64" (or the reverse)

The bundle and the server disagree about the processor. This is caught before anything is
uploaded. Ask your supplier for the bundle matching the architecture named in the message —
`arm64` is AWS Graviton and some ARM machines; almost everything else is `amd64`.

### "The server identity has changed"

`known_hosts` on your PC holds a different key for that address from the one the server is
now offering. Nothing has been sent.

- **If you rebuilt or replaced the server**, that is expected. Remove the old entry, and the
  wizard will ask you to confirm the new identity next time:
  ```
  ssh-keygen -R your-server-address
  ```
- **If you did not**, stop, and ask whoever looks after the server. Do not remove the entry
  to make the message go away.

### "This key is protected by a passphrase"

See the note in section 1. Use a key without one, or remove the passphrase from a **copy**:

```
ssh-keygen -p -f copy-of-the-key.pem
```

### "Docker could not be installed"

Usually the server cannot reach the internet, which it needs once in order to fetch Docker.
Either give it access briefly, or install Docker from your own package mirror, then run the
wizard again.

If it reports Docker was installed **without `docker compose`**, install the
`docker-compose-plugin` package on the server and run the wizard again.

### "This server's Linux is not one this wizard can install Docker on"

The wizard handles Ubuntu, Debian, Amazon Linux, Red Hat, Rocky and AlmaLinux. On anything
else, install Docker yourself following your Linux supplier's instructions, then run the
wizard again — it will find it and carry on.

### The address does not open in a browser

The system is running but the port is closed. Use **Is it running?** on the Manage page to
confirm it is healthy, then open the port in the server firewall or your hosting provider's
security group. With the trusted-certificate option, also check the domain name really
points at the server and that ports 80 and 443 are both open.

### Something else

[TROUBLESHOOTING.md](TROUBLESHOOTING.md) covers the application's own problems, and applies
just the same to a server installation. **Recent activity** on the Manage page shows you the
same log it talks about.

---

## 8. What the wizard does with your passwords and keys

Worth knowing, because you are handing it credentials to a server.

- **The key file is never copied, read into the wizard, or sent anywhere.** It is passed to
  the Windows SSH client by filename, and that is all.
- **The database password and the first administrator password are generated on your PC**
  and travel to the server through the SSH connection itself, into a file that is created
  readable only by your account there. They are never written to a file on your PC, never put
  on a command line, and are masked out of the progress log.
- **The server's administrator (`sudo`) password**, if the wizard needs one, is asked for
  once, kept only in memory while the wizard runs, and sent the same way. It is never saved.
- **Nothing sensitive is ever placed in a command line**, because on both Windows and Linux
  anyone able to list running processes can read those.
- **The server's identity is confirmed by you, once**, and remembered in the standard
  `known_hosts` file in your Windows profile. The wizard never disables that check.

The one thing this cannot protect you from is your own PC: a machine with something hostile
already on it can watch anything you type. Install from a computer you trust.
