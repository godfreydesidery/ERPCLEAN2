### Profit on the sales report: what it could not know, it now says

This one changes figures you have been reading, so it is worth a moment.

The Margin column is your sales less what the goods cost you. For that to work the system has to
know what the goods cost — and for some products it never did: the ones whose stock was entered as
an opening balance, or brought in through a stock upload, without a cost. For those the system was
treating the cost as **nothing**, and so reporting the whole sale as profit.

That is why the margin looked right on some products and far too high on others.

Those lines now read **not costed** instead of showing a profit that was never real, and the total
tells you how many products it has had to leave out. **Expect the margin total to be lower than it
was.** That is the correction, not a loss: the old figure was counting profit that had not been
earned.

To settle a product for good, give it a cost — receive stock at a cost price, or set an opening
cost for it. From then on it counts towards the margin like any other product.

### A stock transfer can be printed and exported

Once a transfer is created you can now print it or save it. **Print / PDF** produces a document to
send with the goods: the transfer number and date, where it is going from and to, and the items and
quantities. **Excel** gives you the same thing as a spreadsheet.

The document deliberately shows no money. A transfer moves your own stock between your own
locations, so there is nothing to charge — and a sheet travelling with the goods should not tell
whoever receives them what you paid for them.

### A line now shows the product you picked

On a transfer, every line kept showing an empty search box beside the product you had already
chosen, so a finished line looked unfinished. The line now shows the product, with **change** if
you want a different one and **clear** to empty it.

---

### Choosing a product is now a search, not a dropdown

In the places where you pick a product there were two controls doing one job: a box to type in, and
a dropdown beside it holding the results. You typed in one and then had to look in the other.

There is now one box. Type part of a name or a product code and the matches appear underneath, ready
to click. Nothing is listed until you type, so you are never handed a long list to scroll through
before you have said what you are looking for.

Once you choose one it is shown under the box with a tick, so there is never any doubt about which
product was actually selected — with a **clear** link beside it if you picked the wrong one.

The keyboard works throughout: the arrow keys move through the matches, Enter chooses the
highlighted one, and Escape closes the list. Enter no longer submits the whole form while you are
still choosing.

Where you pick from a short fixed list — a branch, a location, a price list — nothing changes. Those
remain ordinary dropdowns, which suit a handful of choices better than a search box does.

### An update that cannot finish now says so before it starts

Updating replaces files in the installation folder. If those files belong to a different user
account on the server, it cannot — and it used to discover this half-way through, after the safety
backup had been taken and the new version unpacked, ending in a dozen unexplained "Permission
denied" lines with nothing to say what had gone wrong or what to do about it. Running it again
failed in exactly the same way.

It now checks before it starts, and stops with a plain message naming the files involved and the
single command that fixes them. Nothing on the running system is touched.

One related correction: the file that records which version is installed is now written only once
the new version is actually running. Before, an update that stopped part-way left that file naming a
version that was not running — so the obvious way to check whether an update had worked could
confirm a success that had not happened.

---

### Products missing from the lists where you pick them

When you went to choose a product — on a stock transfer, on a requisition, when setting a price, and
in several other places — some of your products were simply not in the list. Typing into the search
box above the list did not help either: it found nothing, which made it look as though the product
had never been created at all.

The products were always there, and nothing was lost. Those lists only ever loaded the first part of
your catalogue, and the search box only looked **inside that part**. Anything past it was both
missing from the list and impossible to search for. Which products went missing looked random rather
than alphabetical, because the part that loaded was in no particular order.

Typing in one of those boxes now searches your whole catalogue, by product name or by product code,
however many products you have. The list you see before you type is still just a starting point — if
what you want is not on it, type a few letters and it will be found.

This affected, and now works correctly in: stock transfers; purchase requisitions and requests for
quotation; standing orders; batches and serial numbers; the stock movement report; issuing materials
to a job; bills of materials and work orders; and price tiers and customer prices. Pricing was the
worst affected — that screen was loading only the first 20 products.

**Nothing needs re-entering and there is no database change.** Products that were unreachable become
reachable as soon as this update is installed.

---

### A priced product could ring up as nothing at the till

If a product had a price on screen in the back office but the till showed a dash and a total of
0.00, this was why — and it is fixed.

It happened when a product's **unit was changed after its price was set**. The price stayed attached
to the old unit, and the till could no longer see it. Nothing looked wrong anywhere: the price list
showed the amount, the product page showed the amount, and only the till disagreed.

Three things change:

- **Prices like that are found again.** Nothing needs re-entering — every affected product starts
  pricing correctly as soon as this update is installed.
- **You can now delete such a price.** The Remove button used to do nothing at all on one of these
  rows, so the only visible fix was also unavailable.
- **The unit can no longer be changed while a product has prices.** You will be asked to remove the
  prices, change the unit, and enter them again. That is deliberate: a price of 20,000 per carton is
  not 20,000 per piece, and silently keeping the number would have put a wrong price on a receipt
  rather than no price at all.

### The till now says what went wrong

When the system refused a sale, the till often said "No answer from the ERP, so we cannot tell
whether this sale went through. Press Retry" — and retrying could never work, because the sale had
been refused for a reason the till never showed.

It now shows the reason. If a line has no price, it says so.

### TIN and VRN print separately on documents

Documents printed a single line labelled `TIN/VAT:`, which ran two different numbers together. The
TIN identifies the taxpayer and the VRN identifies the VAT registration, and a document headed TAX
INVOICE needs both, stated separately. They now print on their own lines, and the VRN appears from
the company record without anything to re-enter.

### A database update is included

If you are coming from 1.8.3 or earlier, this update also changes the database structure. (If you
are already on 1.9.0 it does not — that change is already in place, and there is nothing further to
apply.) **The upgrade takes a safety backup first and stops if it cannot** — and the change was
rehearsed against a copy of your own live database, taken the same day, before being offered to you.

---

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
