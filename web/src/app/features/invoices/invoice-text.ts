import { Invoice, InvoiceSummary } from '../../core/models';

/** Cents as money in the invoice's currency, with the cents shown: "€1,234.50". */
export function formatCents(cents: number, currency: string): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency, minimumFractionDigits: 2 }).format(cents / 100);
}

/** "Invoice 12", "Credit note 13", or "Draft" before it has a number. */
export function invoiceTitle(invoice: Pick<InvoiceSummary, 'kind' | 'number' | 'status'>): string {
  if (invoice.status === 'DRAFT' || invoice.number === undefined || invoice.number === null) {
    return 'Draft invoice';
  }
  return `${invoice.kind === 'CREDIT_NOTE' ? 'Credit note' : 'Invoice'} ${invoice.number}`;
}

/** Minutes as hours with two decimals, as on an invoice: 90 is "1.50". */
export function invoiceHours(minutes: number): string {
  return (Math.round((minutes / 60) * 100) / 100).toFixed(2);
}

/** What can be done with an invoice now. */
export function invoiceActions(invoice: Pick<Invoice, 'kind' | 'status' | 'creditedById'>): {
  edit: boolean;
  issue: boolean;
  credit: boolean;
} {
  const draft = invoice.status === 'DRAFT';
  return {
    edit: draft,
    issue: draft,
    credit: !draft && invoice.kind === 'INVOICE' && !invoice.creditedById,
  };
}
