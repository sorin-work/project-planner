import { loadLevel } from './workload-cell';

describe('loadLevel', () => {
  it('grades allocation against capacity', () => {
    expect(loadLevel({ allocatedHours: 0, capacityHours: 40 })).toBe('empty');
    expect(loadLevel({ allocatedHours: 10, capacityHours: 40 })).toBe('light');
    expect(loadLevel({ allocatedHours: 30, capacityHours: 40 })).toBe('good');
    expect(loadLevel({ allocatedHours: 36, capacityHours: 40 })).toBe('full');
    expect(loadLevel({ allocatedHours: 40, capacityHours: 40 })).toBe('full');
    expect(loadLevel({ allocatedHours: 41, capacityHours: 40 })).toBe('over');
  });

  it('treats any work for someone without capacity as over', () => {
    expect(loadLevel({ allocatedHours: 2, capacityHours: 0 })).toBe('over');
  });
});
