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
