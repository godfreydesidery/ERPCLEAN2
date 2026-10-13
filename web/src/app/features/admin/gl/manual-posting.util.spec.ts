/** ACC-18: the manual-journal picker marks control accounts with the module to use instead. */
import { manualPostingBlock } from './manual-posting.util';
import type { AccountDto } from './models/gl.model';

const base: AccountDto = {
  id: '1', uid: 'A1', companyId: '10', accountCode: '5200', name: 'Rent', accountType: 'EXPENSE',
  normalBalance: 'DEBIT', active: true, status: 'ACTIVE',
};

describe('manualPostingBlock', () => {
  it('lets ordinary accounts through', () => {
    expect(manualPostingBlock(base)).toBeNull();
    expect(manualPostingBlock({ ...base, allowManualPosting: true, controlType: null })).toBeNull();
  });

  it('names the module for blocking control types', () => {
    expect(manualPostingBlock({ ...base, controlType: 'AR', allowManualPosting: false })).toContain('Receivables');
    expect(manualPostingBlock({ ...base, controlType: 'AP' })).toContain('Payables');
    expect(manualPostingBlock({ ...base, controlType: 'INVENTORY' })).toContain('Stock');
    expect(manualPostingBlock({ ...base, controlType: 'TAX' })).toContain('VAT Return');
  });

  it('keeps cash and bank postable, unless the flag closes them', () => {
    expect(manualPostingBlock({ ...base, controlType: 'CASH', allowManualPosting: true })).toBeNull();
    expect(manualPostingBlock({ ...base, controlType: 'BANK', allowManualPosting: false }))
      .toBe('closed to manual journals');
  });
});
