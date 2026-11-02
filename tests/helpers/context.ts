import type { RequestContext } from '../../src/core/context';

export const superAdmin: RequestContext = {
  userId: 1,
  loginId: 'sa',
  customerId: null,
  userType: 'S',
};

export function adminOf(customerId: number, loginId = `admin${customerId}`): RequestContext {
  return { userId: 100 + customerId, loginId, customerId, userType: 'A' };
}

export function operatorOf(customerId: number): RequestContext {
  return { userId: 200 + customerId, loginId: `op${customerId}`, customerId, userType: 'O' };
}
