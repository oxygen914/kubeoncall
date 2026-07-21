import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { useSession } from './useSession'
import { sanitizeReturnTo } from './sessionUtils'
import { mapLoginError } from './loginErrors'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'

const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名').max(128),
  password: z.string().min(1, '请输入密码').max(1024),
})

type LoginFormValues = z.infer<typeof loginSchema>

export function LoginPage() {
  const { session, loading, login } = useSession()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  })

  const returnTo = sanitizeReturnTo(searchParams.get('returnTo') ?? '/')

  // If already authenticated (e.g. user navigates to /login manually), bounce.
  if (!loading && session?.authenticated) {
    return <Navigate to={returnTo} replace />
  }

  const onSubmit = async (values: LoginFormValues) => {
    setSubmitError(null)
    try {
      await login(values.username, values.password)
      navigate(returnTo, { replace: true })
    } catch (err) {
      setSubmitError(mapLoginError(err))
    }
  }

  return (
    <main className="koc-login" aria-labelledby="login-title">
      <div className="koc-login__card">
        <h1 id="login-title" className="koc-login__title">
          KubeOnCall Console
        </h1>
        <p className="koc-login__subtitle">登录以继续</p>

        <form className="koc-login__form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <div className="koc-field">
            <label htmlFor="username">用户名</label>
            <Input
              id="username"
              autoComplete="username"
              aria-invalid={!!errors.username}
              aria-describedby={errors.username ? 'username-error' : undefined}
              {...register('username')}
            />
            {errors.username && (
              <span id="username-error" role="alert" className="koc-field__error">
                {errors.username.message}
              </span>
            )}
          </div>

          <div className="koc-field">
            <label htmlFor="password">密码</label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              aria-invalid={!!errors.password}
              aria-describedby={errors.password ? 'password-error' : undefined}
              {...register('password')}
            />
            {errors.password && (
              <span id="password-error" role="alert" className="koc-field__error">
                {errors.password.message}
              </span>
            )}
          </div>

          {submitError && (
            <div role="alert" className="koc-login__alert">
              {submitError}
            </div>
          )}

          <Button type="submit" disabled={isSubmitting} fullWidth>
            {isSubmitting ? <Spinner size="sm" /> : '登录'}
          </Button>
        </form>
      </div>
    </main>
  )
}
