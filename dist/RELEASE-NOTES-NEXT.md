### Restoring a backup is now safe

The `restore` command has been rewritten. Three things were wrong with it, and all three
could leave you with a database that was only partly rolled back while the screen said
**Restore complete**.

- **It now takes a safety copy of your current database before it starts.** Previously it did
  not, so restoring the wrong file destroyed what you had with no way back. If that safety copy
  cannot be made, the restore stops and nothing is changed.
- **It now clears the database properly before restoring.** The old method removed things one at
  a time in the order the backup listed them, which failed whenever your live database contained
  something newer than the backup.
- **It now stops and tells you if anything goes wrong**, instead of reporting success. If a
  restore does fail, the message names the safety copy so you can return to where you were.

The safety copy is kept in `backups/` alongside your normal backups.

### The desktop shortcut works again

`OrbixERP.cmd` — the icon on the desktop, and the file the guides tell you to use — looked for a
file under the wrong name. Opening it printed *"This folder looks incomplete. Unpack the bundle
again."* and closed, on every version from 1.1.0 onwards. It now opens correctly.

If you have been running commands by hand because the icon did not work, you no longer need to.

### Groundwork you will not see

This release adds some new, empty columns to the database in preparation for future work. Nothing
in the system behaves differently because of them, and no screen changes. They are listed here only
so the update log makes sense if you look at it.

### Nothing to do after updating

There is no configuration change, no new setting, and no action required from you or your staff.
Log in and carry on as normal.
