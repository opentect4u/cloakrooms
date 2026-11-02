import { Router } from 'express';
import { asyncHandler } from '../../core/http/asyncHandler';
import { health, ready } from './health.controller';

export const healthRoutes = Router();

healthRoutes.get('/health', health);
healthRoutes.get('/ready', asyncHandler(ready));
