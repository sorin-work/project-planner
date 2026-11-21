import { formatCents, invoiceActions, invoiceHours, invoiceTitle } from './invoice-text';

describe('invoice text', () => {
  it('shows cents as money with the cents', () => {
    expect(formatCents(123450, 'EUR')).toBe('€1,234.50');
    expect(formatCents(-2334, 'GBP')).toBe('-£23.34');
    expect(formatCents(5, 'USD')).toBe('US$0.05');
  });

  it('names an invoice by its number once it has one', () => {
    expect(invoiceTitle({ kind: 'INVOICE', status: 'DRAFT' })).toBe('Draft invoice');
    expect(invoiceTitle({ kind: 'INVOICE', status: 'ISSUED', number: 12 })).toBe('Invoice 12');
    expect(invoiceTitle({ kind: 'CREDIT_NOTE', status: 'ISSUED', number: 13 })).toBe('Credit note 13');
  });

  it('shows hours with two decimals', () => {
    expect(invoiceHours(90)).toBe('1.50');
    expect(invoiceHours(7)).toBe('0.12');
    expect(invoiceHours(14)).toBe('0.23');
  });

  it('only lets a draft change, and an issued invoice be credited once', () => {
    expect(invoiceActions({ kind: 'INVOICE', status: 'DRAFT' })).toEqual({ edit: true, issue: true, credit: false });
    expect(invoiceActions({ kind: 'INVOICE', status: 'ISSUED' })).toEqual({ edit: false, issue: false, credit: true });
    expect(invoiceActions({ kind: 'INVOICE', status: 'ISSUED', creditedById: 4 }).credit).toBe(false);
    expect(invoiceActions({ kind: 'CREDIT_NOTE', status: 'ISSUED' }).credit).toBe(false);
  });
});
