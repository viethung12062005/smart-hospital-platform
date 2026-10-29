import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: false,
        // Không thử lại lỗi 4xx (quyền, dữ liệu sai): thử lại không làm kết quả khác đi.
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      },
    },
  });
}
