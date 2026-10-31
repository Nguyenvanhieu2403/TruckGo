import { describe, expect, it } from 'vitest';
import { parseEvent } from './trip-stream';

describe('parseEvent', () => {
  it('parses named events with JSON data', () => {
    expect(parseEvent('event:location\ndata:{"state":"LIVE","latitude":21.02}')).toEqual({ type: 'location', data: { state: 'LIVE', latitude: 21.02 } });
    expect(parseEvent('event: status\ndata: {"status":"DELIVERED"}')).toEqual({ type: 'status', data: { status: 'DELIVERED' } });
  });

  it('ignores heartbeats, unknown events and broken data', () => {
    expect(parseEvent(':hb')).toBeNull();
    expect(parseEvent('event:other\ndata:{}')).toBeNull();
    expect(parseEvent('event:location\ndata:{not json')).toBeNull();
  });
});
