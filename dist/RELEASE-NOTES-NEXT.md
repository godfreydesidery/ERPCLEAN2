### Your administrator can now hand out the standard job roles

The twelve standard roles that ship with OrbixERP — Cashier, Salesperson, Accountant, Storekeeper,
Procurement Officer and the rest — could not previously be given to anyone by your own
administrator. Only we could. In practice that meant either waiting for us, or someone being made a
full system owner just so they could get on with their work.

Your administrator can now assign any of those roles to your staff directly, with one limit that
matters: **they can only give away what they hold themselves.** Someone who cannot post to the
general ledger cannot grant that ability to anybody else. Nobody can promote themselves.

### The last administrator can no longer be removed by accident

Removing the administrator role from the only person who has it used to be allowed. It left nobody
able to add a user, grant a role or undo the mistake — and the only way back was for us to repair it
directly in the database.

That is now refused, with a message explaining why. Give someone else the administrator role first,
then remove it from the original person.

### Approval attempts at the till

When a cashier asks a supervisor to approve something — a discount, a price override — and the
supervisor's password is correct but they are **not allowed** to approve that particular action,
that attempt now counts towards the same short cool-down as a wrong password.

This closes a gap. Previously those attempts were unlimited, which meant someone could stand at a
till and check a colleague's password over and over without ever being locked out.

The message on screen is unchanged and still tells the operator plainly that the person is not
allowed to approve the action — it does not pretend the password was wrong.

### Withdrawn branch access now takes effect properly

If a user's access to a branch is withdrawn, they could still switch their session into that branch.
They were then refused the branch's reports, which looked like a broken screen rather than a
withdrawn permission. Withdrawn access is now honoured at the point of switching.

We checked your live system before making this change: **no branch access has been withdrawn on your
installation**, so nobody currently working is affected.

### Branch switching appears in the audit trail

Moving between branches was not recorded anywhere. Where the move crosses from one company to
another it is now written to the audit trail, with who did it and when.

### Removing a system owner now takes effect immediately

If someone's system-owner status is removed, it previously stayed in force until their existing
sign-in expired — up to fifteen minutes. It now applies on their next action.

### Groundwork you will not see

Most of this release is preparation for running several separate businesses on one installation.
None of it changes anything on a single installation like yours: the same screens, the same data,
the same numbers. It is mentioned only so the update log makes sense if you look at it.
