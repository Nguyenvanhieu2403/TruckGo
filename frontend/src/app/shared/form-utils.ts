import { AbstractControl, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Mirrors the backend password policy: 8–72 characters with at least one letter and one digit. */
export const passwordPolicy: ValidatorFn = control => {
  const value = String(control.value ?? '');
  if (!value) return null;
  const ok = value.length >= 8 && new TextEncoder().encode(value).length <= 72 && /\p{L}/u.test(value) && /\d/.test(value);
  return ok ? null : { passwordPolicy: true };
};

export const VN_PHONE_PATTERN = /^(\+84|84|0)?[\s.-]*[35789]([\s.-]*\d){8}$/;

export function matchFields(field: string, confirm: string): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const a = group.get(field)?.value;
    const b = group.get(confirm)?.value;
    return a && b && a !== b ? { mismatch: true } : null;
  };
}

/** Puts backend field errors onto matching controls as `{ server: message }`. Returns messages for unknown fields. */
export function applyServerErrors(form: FormGroup, errors: Record<string, string>): string[] {
  const unmatched: string[] = [];
  for (const [field, message] of Object.entries(errors)) {
    const control = form.get(field);
    if (control) { control.setErrors({ ...(control.errors ?? {}), server: message }); control.markAsTouched(); }
    else unmatched.push(message);
  }
  return unmatched;
}

export function fieldError(control: AbstractControl | null, messages: Record<string, string>): string | null {
  if (!control || !control.invalid || !(control.touched || control.dirty)) return null;
  const errors = control.errors ?? {};
  if (typeof errors['server'] === 'string') return errors['server'];
  const key = Object.keys(messages).find(k => k in errors);
  return key ? messages[key] : null;
}
