import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  useSession: vi.fn(),
  useKnowledgeDocuments: vi.fn(),
  useKnowledgeImports: vi.fn(),
  useCreateKnowledgeImport: vi.fn(),
  useMemories: vi.fn(),
  useMemoryExtractions: vi.fn(),
  useCreateMemoryExtraction: vi.fn(),
  useSkills: vi.fn(),
  useReloadSkills: vi.fn(),
}))

vi.mock('@/features/auth/useSession', () => ({ useSession: mocks.useSession }))
vi.mock('@/features/tasks/TaskStatusPanel', () => ({
  TaskStatusPanel: ({ taskId }: { taskId: string }) => <div>task:{taskId}</div>,
}))
vi.mock('@/features/knowledge/hooks', () => ({
  useKnowledgeDocuments: mocks.useKnowledgeDocuments,
  useKnowledgeImports: mocks.useKnowledgeImports,
  useCreateKnowledgeImport: mocks.useCreateKnowledgeImport,
}))
vi.mock('@/features/memories/hooks', () => ({
  useMemories: mocks.useMemories,
  useMemoryExtractions: mocks.useMemoryExtractions,
  useCreateMemoryExtraction: mocks.useCreateMemoryExtraction,
}))
vi.mock('@/features/skills/hooks', () => ({
  useSkills: mocks.useSkills,
  useReloadSkills: mocks.useReloadSkills,
}))

import { KnowledgeListPage } from '@/features/knowledge/KnowledgeListPage'
import { MemoryListPage } from '@/features/memories/MemoryListPage'
import { SkillsListPage } from '@/features/skills/SkillsListPage'

const emptyPage = {
  data: [],
  page: { number: 1, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
}

function queryResult() {
  return {
    data: emptyPage,
    isLoading: false,
    isFetching: false,
    error: null,
  }
}

function mutationResult(mutate = vi.fn()) {
  return {
    mutate,
    data: undefined,
    error: null,
    isError: false,
    isPending: false,
  }
}

function session(permissions: string[]) {
  mocks.useSession.mockReturnValue({
    session: {
      authenticated: true,
      user: {
        id: 'usr_1',
        username: 'operator',
        displayName: 'Operator',
        roles: [],
        permissions,
      },
      expiresAt: '2026-07-22T00:00:00Z',
    },
  })
}

describe('WBS-9 governance pages', () => {
  it('offers multipart knowledge import only to a writer', () => {
    session(['knowledge:read', 'knowledge:write'])
    mocks.useKnowledgeDocuments.mockReturnValue(queryResult())
    mocks.useKnowledgeImports.mockReturnValue(queryResult())
    mocks.useCreateKnowledgeImport.mockReturnValue(mutationResult())

    render(
      <MemoryRouter>
        <KnowledgeListPage />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: '导入知识' }))

    expect(screen.getByRole('heading', { name: '创建导入任务' })).toBeInTheDocument()
    expect(screen.getByLabelText('文件')).toHaveAttribute('type', 'file')
  })

  it('offers durable memory extraction and shows recent extraction state', () => {
    session(['memory:read', 'memory:write'])
    mocks.useMemories.mockReturnValue(queryResult())
    mocks.useMemoryExtractions.mockReturnValue({
      ...queryResult(),
      data: {
        ...emptyPage,
        data: [
          {
            id: 'mext_1',
            sourceType: 'EXECUTION',
            sourcePublicId: 'exec_1',
            status: 'RUNNING',
            evidenceCount: 3,
            memoryCount: 1,
            updatedAt: '2026-07-21T00:00:00Z',
          },
        ],
      },
    })
    mocks.useCreateMemoryExtraction.mockReturnValue(mutationResult())

    render(
      <MemoryRouter>
        <MemoryListPage />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: '创建提取任务' }))

    expect(screen.getByRole('heading', { name: '结构化提取' })).toBeInTheDocument()
    expect(screen.getByText('mext_1')).toBeInTheDocument()
    expect(screen.getByText('RUNNING')).toBeInTheDocument()
  })

  it('submits a durable Skill reload task from the state list', () => {
    const mutate = vi.fn()
    session(['skill:read', 'skill:manage'])
    mocks.useSkills.mockReturnValue(queryResult())
    mocks.useReloadSkills.mockReturnValue(mutationResult(mutate))

    render(
      <MemoryRouter>
        <SkillsListPage />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }))

    expect(mutate).toHaveBeenCalledOnce()
  })
})
