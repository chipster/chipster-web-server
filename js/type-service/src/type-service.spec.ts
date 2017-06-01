
import TypeService from './type-service';
import { expect } from 'chai';

describe('Test testing', () => {
  it('should return true', () => {
    expect(TypeService.pValueAndFoldChangeCompatible(["pppvalue", "FaaC", "logFC", "happy", "times", "p.test"])).to.equal(true);
  });
});
