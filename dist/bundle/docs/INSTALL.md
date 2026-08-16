# Installing OrbixERP

This guide takes about 15 minutes. You do not need to be a developer.

> **Installing onto a server somewhere else?** If OrbixERP is to run on a Linux server — in
> your server room, or with a hosting provider such as AWS — rather than on the computer in
> front of you, double-click **`Remote-Setup.cmd`** instead and follow
> [REMOTE-INSTALL.md](REMOTE-INSTALL.md). That wizard runs on your Windows PC, sends
> everything to the server over the network, installs it there, and afterwards checks, backs
> up, restarts and updates it — without you ever typing a command on the server. The rest of
> this guide is for installing on **this** computer.

---

## 1. What you need first

**A computer to run it on.** It can be a server or an ordinary desktop PC that stays
switched on. Everyone else uses it through a web browser over your network — nothing is
installed on their machines.

| | Minimum | Comfortable |
|---|---|---|
| Memory (RAM) | 4 GB | 8 GB |
| Free disk space | 20 GB | 50 GB+ |
| Operating system | Windows 10/11, Windows Server 2019+, or a recent Linux | — |

**Docker.** This is the only software you must install yourself.

- **Windows** — download **Docker Desktop** from [docker.com](https://www.docker.com/products/docker-desktop/).
  Run the installer, accept the **WSL 2** option when offered, and restart if asked. Start
  Docker Desktop and wait until the whale icon in the system tray stops animating.
- **Linux** — run:
  ```bash
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker $USER
  ```
  Then **log out and log back in** so the group change takes effect.

**The bundle you were sent** — a `.zip` file named something like `orbixerp-1.0.0-amd64.zip`.

> **amd64 or arm64?** Almost every PC and server is `amd64`, and that is the bundle you
> will normally have. `arm64` is for Apple Silicon Macs, AWS Graviton servers and some ARM
> mini-PCs. If you have the wrong one the installer says so immediately and changes nothing.

---

## 2. Unpack it

Unzip the file anywhere convenient.

**On Windows** the wizard asks where to install and creates that folder itself, so where you
unzip does not matter much — the Downloads folder is fine for the bundle.

**On Linux/macOS**, unpack it somewhere permanent such as `/opt/orbixerp`: that folder becomes the
home of your system, its settings and its backups.

### Where it installs, and why

OrbixERP installs into **its own folder** — `C:\OrbixERP` on Windows, `/opt/orbixerp` on
Linux — never inside Windows Program Files or a Linux system directory. **Both installers
ask where to put it, and both refuse a system location.**

That is deliberate, and it is the same approach Bitnami and similar stacks take:

- **No administrator or root rights** are needed to install it, run it, update it or back it
  up. An installation placed in a system directory would need them forever afterwards.
- **Everything lives in one tree** — the application, your settings, your security keys and
  your backups. You can copy that one folder to another machine, or hand it to your IT
  provider, and nothing is left behind in the registry or scattered across the filesystem.
- **System directories are actively hostile to this.** On Windows, file virtualisation and
  Controlled Folder Access silently redirect or block writes, producing faults that surface
  months later. On Linux, package management owns those paths.

Any ordinary location works: `D:\OrbixERP` on Windows if disk space is tight, or
`/home/you/orbixerp` on Linux if you have no access to `/opt`. Both installers also warn —
but allow — temporary or synchronised folders such as Downloads, Desktop and `/tmp`, because
your security keys and backups live in the install folder.

---

## 3. Decide where your data will live

You have two options. If you are unsure, choose the first.

### Option A — "docker" (recommended)

We run the database for you inside Docker. Nothing extra to install, nothing to configure.
Your data is stored on this machine and survives restarts and upgrades.

Choose this unless someone has told you otherwise.

### Option B — "host"

You already run a PostgreSQL 15 server and want OrbixERP to use it — usually because your
IT team already backs it up and monitors it.

If you choose this, **read [HOST-DB-SETUP.md](HOST-DB-SETUP.md) and do what it says before
running the installer.** The database and its login must exist beforehand.

You can move from one option to the other later; see [OPERATIONS.md](OPERATIONS.md).

---

## 4. Run the installer

### Windows

**Double-click `Setup.cmd`.** A window opens and walks you through everything.

> Windows may show *"Windows protected your PC"* or a *Security Warning*, because the file
> arrived from outside this computer. Click **More info → Run anyway**, or **Run**. This is
> normal for any downloaded installer.

The wizard will:

1. check this computer has what it needs, and say plainly what is missing if not
2. ask **where to install** — it creates the folder for you
3. ask whether to **install a database** or use one you already run
4. ask for your **organisation, company, branch, timezone and currency**
5. ask which **port** people should use, and whether other computers may connect
6. show you everything for review — nothing is changed until you click **Install**
7. do the work, showing progress, and finally display your **administrator password**

You do not need to prepare anything beforehand, and nothing is downloaded.

<details>
<summary>No wizard — I'd rather use a plain window, or install remotely</summary>

**Double-click `Install.cmd`** for the same installation in a text window. It asks the same
questions, one at a time. Leave the window open until it finishes — it prints your
administrator password at the end.

Or type it yourself, from PowerShell in this folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

To install onto a **remote Linux server** instead, double-click `Remote-Setup.cmd` — see
[REMOTE-INSTALL.md](REMOTE-INSTALL.md).

`-ExecutionPolicy Bypass` is needed because Windows blocks PowerShell scripts that came from
elsewhere. It applies to that one command and changes nothing permanently — which is exactly
what `Setup.cmd` and `Install.cmd` do for you.
</details>

### Linux / macOS

```bash
cd /opt/orbixerp
chmod +x install.sh orbixerp.sh
./install.sh
```

### What you will be asked

The wizard presents these on separate pages; the text installers ask them one at a time,
where you press Enter to accept the suggestion in brackets.

| Question | What to answer |
|---|---|
| Database — `docker` or `host` | See section 3 above |
| Port for users to reach the system | `8080` unless that port is already used |
| Organisation name | Your group or organisation, e.g. *Tembo Group* |
| Company name | The trading company, e.g. *Tembo Trading Ltd* |
| Main branch / location name | e.g. *Head Office* |
| Timezone | e.g. `Africa/Dar_es_Salaam` |
| Currency the accounts are kept in | e.g. `TZS` |

> **The currency is difficult to change later.** It is the currency your accounts are kept
> in. Take a moment over it.

Everything else — passwords, security keys — is generated for you.

The installer then loads the software, checks everything, and starts the system. **The
first start takes several minutes** because it is creating the database structure. You
will see a row of dots. This is normal and only happens once.

---

## 5. Sign in

When it finishes, the installer prints something like:

```
  Open in a browser
      on this machine   http://localhost:8080
      from the network  http://SERVER-PC:8080

  Sign in with
      username          rootadmin
      password          k3Rm9tXpQw2LvB8nZcAe
```

Open that address in a browser and sign in.

**Do these two things immediately:**

1. **Change the `rootadmin` password** from inside the application.
2. **Write down the address** other people should use — the "from the network" one.

If colleagues cannot reach that address, see
[TROUBLESHOOTING.md](TROUBLESHOOTING.md#other-computers-cannot-reach-the-system).

---

## 6. Backups — half of this is already done

**The installer has scheduled a backup every night at 02:00.** You do not need to set that
up, and the summary it printed says so. Two things are still worth doing today.

### Check it exists

```bash
crontab -l                                          # Linux / macOS
Get-ScheduledTask -TaskPath '\OrbixERP\'            # Windows
```

You should see one OrbixERP entry. If the installer said it could **not** schedule the
backup — on Windows this happens when it was not started as an administrator — it printed
exactly what to do instead. Go back and do that now; it is the difference between having
backups and intending to.

Take one straight away as well, so you have seen it work:

```bash
./orbixerp.sh backup           # Linux / macOS
.\orbixerp.ps1 backup          # Windows
```

### Arrange to get them off this machine — nothing does this for you

The schedule writes backups to this machine's own disk, and stops there. A backup on the
computer that failed is not a backup. Set up a copy to another computer, a NAS, cloud
storage, or an external drive that physically leaves the building.

Three things must be copied **together**, or you will have a database you cannot sign into:

```
backups/     the data
.env         the settings and passwords
secrets/     the sign-in security keys
```

> If this system is **shared with another organisation**, do not copy these yourself — the
> file contains their data as well as yours. See *When the system is shared by more than one
> organisation* in [OPERATIONS.md](OPERATIONS.md).

[OPERATIONS.md](OPERATIONS.md) explains how to change the time, check the schedule is still
running, and restore.

---

## Everyday use

**On Windows, double-click `OrbixERP.cmd`** and pick a number:

```
    1   Start the system
    2   Stop the system            (your data is kept)
    3   Is it running?
    4   Back up the database
    5   Show recent activity
    6   Open it in a web browser
```

**On Linux / macOS**, or if you prefer typing, run these from the folder you installed into:

| Task | Linux / macOS | Windows |
|---|---|---|
| Is it running? | `./orbixerp.sh status` | `.\orbixerp.ps1 status` |
| Start | `./orbixerp.sh start` | `.\orbixerp.ps1 start` |
| Stop | `./orbixerp.sh stop` | `.\orbixerp.ps1 stop` |
| Back up | `./orbixerp.sh backup` | `.\orbixerp.ps1 backup` |
| View logs | `./orbixerp.sh logs` | `.\orbixerp.ps1 logs` |

> **Never run `docker compose down -v`.** The `-v` deletes the database and everything in
> it. `orbixerp stop` is always the safe way to shut down.

---

## If something goes wrong

Everything the installer does is safe to repeat. If it stops with an error, fix what it
describes and run it again — it will not overwrite your settings, regenerate your security
keys, or touch an existing database.

[TROUBLESHOOTING.md](TROUBLESHOOTING.md) covers the common problems.
