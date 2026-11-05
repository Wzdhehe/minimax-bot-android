// SPDX-License-Identifier: AGPL-3.0-only

const MAX_BYTES = 1024 * 1024;
const failure = (code) => Object.assign(new Error(code), { code });
const knownReadErrors = new Set(['runtime_data_denied', 'runtime_data_closed', 'runtime_data_unavailable', 'runtime_data_too_large', 'runtime_data_not_found']);

/** One trusted runtime's shared file capability; this is not an OS lease.
 * The native owner must still enforce exclusive runtime/process lifetime. */
export function createBotFileLease(files) {
  if (typeof files?.read !== 'function' || typeof files?.replace !== 'function' || typeof files?.append !== 'function') {
    throw new TypeError('A trusted Bot file capability is required');
  }
  let invalidated;
  let mutationTail = Promise.resolve();
  let pending = 0;
  const healthy = () => { if (invalidated) throw failure(invalidated); };
  const invalidate = (code = 'runtime_data_reload_required') => {
    if (code === 'runtime_data_outcome_unknown') invalidated = code;
    else if (!invalidated) invalidated = 'runtime_data_reload_required';
  };
  function name(value) {
    if (typeof value !== 'string' || !/^[A-Za-z0-9._-]{1,128}$/.test(value) || value === '.' || value === '..' || value.startsWith('.native-publish-')) {
      throw failure('runtime_data_invalid');
    }
    return value;
  }
  function snapshot(bytes) {
    if (!(bytes instanceof Uint8Array)) { invalidate(); throw failure('runtime_data_invalid'); }
    const backing = bytes.buffer;
    if ((typeof SharedArrayBuffer !== 'undefined' && backing instanceof SharedArrayBuffer) || backing.resizable === true || backing.detached === true) {
      invalidate(); throw failure('runtime_data_invalid');
    }
    if (bytes.byteLength > MAX_BYTES) { invalidate(); throw failure('runtime_data_too_large'); }
    return Uint8Array.from(bytes);
  }
  async function query(method, filename) {
    healthy();
    name(filename);
    if (typeof files[method] !== 'function') throw failure('runtime_data_unavailable');
    let result;
    try { result = await files[method](filename); }
    catch (error) {
      healthy();
      throw failure(knownReadErrors.has(error?.code) ? error.code : 'runtime_data_unavailable');
    }
    healthy();
    return result;
  }
  function mutate(method, args, allowed) {
    healthy();
    if (pending >= 32) { invalidate(); throw failure('runtime_data_unavailable'); }
    pending++;
    const operation = mutationTail.then(async () => {
      healthy();
      if (typeof files[method] !== 'function') { invalidate(); throw failure('runtime_data_unavailable'); }
      let result;
      try { result = await files[method](...args); }
      catch (error) {
        // Native reports these before publication. Missing is expected for
        // rename/remove; size failures still require Store memory to reload.
        if (error?.code === 'runtime_data_not_found' && (method === 'rename' || method === 'remove')) {
          healthy();
          throw failure('runtime_data_not_found');
        }
        if (error?.code === 'runtime_data_too_large') { invalidate(); throw failure('runtime_data_too_large'); }
        invalidate('runtime_data_outcome_unknown');
        throw failure('runtime_data_outcome_unknown');
      }
      if (!allowed.includes(result)) { invalidate('runtime_data_outcome_unknown'); throw failure('runtime_data_outcome_unknown'); }
      healthy();
      return result;
    });
    mutationTail = operation.then(() => undefined, () => undefined);
    return operation.finally(() => { pending--; });
  }
  return Object.freeze({
    healthy,
    invalidate,
    supports(methods) { return Array.isArray(methods) && methods.every((method) => typeof files[method] === 'function'); },
    async read(filename) {
      const bytes = await query('read', filename);
      if (!(bytes instanceof Uint8Array) || bytes.byteLength > MAX_BYTES) throw failure('runtime_data_unavailable');
      return bytes;
    },
    async size(filename) {
      const result = await query('size', filename);
      if (!Number.isSafeInteger(result) || result < 0) throw failure('runtime_data_unavailable');
      return result;
    },
    replace(filename, bytes) { healthy(); return mutate('replace', [name(filename), snapshot(bytes)], ['published']); },
    append(filename, bytes) { healthy(); return mutate('append', [name(filename), snapshot(bytes)], ['published']); },
    publish(filename, bytes) { healthy(); return mutate('publish', [name(filename), snapshot(bytes)], ['published', 'already_exists']); },
    rename(source, target) {
      healthy();
      name(source); name(target);
      if (source === target) throw failure('runtime_data_invalid');
      return mutate('rename', [source, target], ['published']);
    },
    remove(filename) { healthy(); return mutate('remove', [name(filename)], ['removed']); },
  });
}
