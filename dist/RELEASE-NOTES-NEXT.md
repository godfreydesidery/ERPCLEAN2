### You stay signed in while you work

The web app signed everyone out about 15 minutes after they signed in, even in the middle of
work. It now renews the session quietly in the background, so you stay signed in for as long as
you keep using it, and you stay on the branch you had switched to.

### Till sales: who rang them, and which shift

Every sale now records, for the till to read back, which till shift it was rung in and who rang
it. The new till version below uses this for reprints, refunds and the Today's sales list. No
database change is involved.

### The till: a new OrbixPOS version

**OrbixPOS 1.5.4** is available alongside this update and is installed separately on each till.
Update the server first: the new till needs this server version for the improvements marked *.

- **Sales are credited to the right person.** A user who could see the sales-agent list (a Sales
  Manager, for example) had every till sale credited to whichever agent came first in that list.
  Every sale is now credited to the person signed in. Please check agent sales reports for past
  sales rung by such users.
- **A manager can print their own Z-read** at the till. It used to need a second manager.
- \* **Refund / reverse** only appears on sales that user may refund.
- \* **Reprints name the cashier who rang the sale**, not whoever is reprinting it.
- **The cash drawer opens only for the sale itself**, never on a reprint or a gift receipt.
- \* **Today's sales** lists only today's till sales at the branch, with reversed sales marked.
- **Cancel on the age check always stops the sale**; overriding it is a separate, explicit button.
- **New: Till expense**, to record cash paid out for the business by category.
- **The version shows on screen.**

Each till's zip includes a full installation guide (README.txt), the release notes for every
version, and the updated user manual.
