import { Route, Routes } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { PublicLayout } from '@/components/PublicLayout'
import { RequireAuth } from '@/lib/auth'
import { DashboardPage } from '@/pages/DashboardPage'
import { DocsPage } from '@/pages/DocsPage'
import { LandingPage } from '@/pages/LandingPage'
import { LoginPage } from '@/pages/LoginPage'
import { ReviewDetailPage } from '@/pages/ReviewDetailPage'

export function App() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route path="/" element={<LandingPage />} />
        <Route path="/docs" element={<DocsPage />} />
      </Route>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/reviews/:id" element={<ReviewDetailPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
