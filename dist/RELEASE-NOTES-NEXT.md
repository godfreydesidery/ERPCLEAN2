### Backups are now taken automatically, every night

Until now the installer created a `backups` folder and the guides asked you to set up a nightly
backup yourself, in cron or Task Scheduler. **The installer now does it** — a backup every night at
02:00, into the same folder as before.

You can move it or turn it off by running the installer again with `--backup-time 03:30` /
`-BackupTime 03:30`, or `--no-schedule` / `-NoSchedule`. Running it again replaces the schedule
rather than adding a second one.

Two things worth knowing:

- **On Windows, adding a scheduled task usually needs administrator rights.** If Setup was not
  started with "Run as administrator", it now says so plainly and prints exactly what to do,
  instead of failing quietly. The system itself installs and runs either way.
- **Nothing copies backups off the machine.** That part is still yours to arrange, and a backup on
  the computer that failed is not a backup. OPERATIONS.md says more.

### Old backups are cleaned up properly

Backups used to be deleted after 14 days — but only the ordinary ones. Two other kinds were never
cleaned up at all, and one important kind was deleted too soon:

- The copy taken automatically **before an upgrade** — the only way to undo one — was thrown away
  after 14 days. It is now kept for 90.
- The copy taken automatically **before a restore** was never deleted at all, and built up for ever.
  It is now kept for 30 days.

There are also new limits so the folder can never fill the disk, and a floor so it can never empty
itself: at least the 7 newest backups are always kept, whatever their age. All of it is adjustable
in `.env`, and the settings are listed in OPERATIONS.md.

### Backups fail more safely

- If a backup fails part-way, the incomplete file is now **deleted**. Before, it stayed in the
  folder looking exactly like a good backup — and could have been chosen to restore from.
- A backup now refuses to start if the disk is too full to finish, rather than running out of space
  half-way through.

### Restoring the right file

If you restored a backup from a USB drive or another folder, and a file of the same name already
existed in `backups`, the system restored **that** one instead — and said it had succeeded. It now
always restores the file you named.

The restore also warns, before it starts, that it replaces the whole database — which matters on a
system shared by more than one organisation, where it takes everybody back, not just you.

### One correction in the guides

The instructions for moving to another machine said to stop the system and then take a backup. That
cannot work: the backup reads the database through the running system. It now says to back up
first, then stop.

### Standing orders now actually run

Standing orders have never generated anything — on any version, since the feature shipped. The
nightly job that creates them ran with no signed-in user behind it, was refused by the system's own
permission check, and the failure was written to a log nobody reads. Nothing appeared, and nothing
said why.

If you have never used standing orders, nothing changes for you. If you set one up in the past and
quietly gave up on it, it will work now.

### The system can no longer be shut down by accident

An administrator could suspend the organisation they were signed in to — which locked out everyone,
including the administrator, with no way back in through the product. Recovering from it needed us to
work directly on your database.

That is now refused with a plain message. As a second safeguard, if it ever happens by some other
route, the system owner can still sign in and undo it.

### Employee records are properly checked against your company

When adding an employee, the branch and the linked user account are now verified to belong to your
company before the record is saved. Previously the branch was only checked to exist somewhere, and
the user account was not checked at all.

### Background jobs no longer queue behind one another

Scheduled work — sending notifications, the nightly standing-order run, and the background dispatcher
— all shared a single worker. A slow job delayed the rest; the hourly notification scan walks through
every company in turn, and while it ran the nightly order run could not start. They now run on four
workers.

### Adding a user shows the full username

When you add a user, the form now shows the full username that will be created, including your
organisation's suffix, before you save. Give the new person that full name — it is what they type to
sign in. Previously the form showed only the part you typed, so it was easy to hand out a name that
would not work.

### Support diagnostics

Log entries now carry the organisation, so a support question can be traced without sifting through
unrelated activity. Nothing on screen changes.
