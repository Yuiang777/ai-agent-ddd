import { useState } from 'react'
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material'
import SectionCard from '../SectionCard'
import MatterFileUpload from './MatterFileUpload'
import { formatTime } from '../../api/matters'
import { formatDeadlineInZone, normalizeDeadlineTime } from '../../api/deadlineTime'

const sources = { CLIENT_SUBMITTED: '客户提供', COURT_RECORD: '法院记录', PUBLIC_RECORD: '公开记录', INTERNAL_SYSTEM: '内部系统', OTHER: '其他来源' }
const preservation = { UNVERIFIED: '未核验', PRESERVATION_REQUESTED: '已申请保全', PRESERVED: '已保全', RELEASED: '已解除' }
const bases = { STATUTORY: '法定期限', COURT_ORDER: '法院命令', TRIBUNAL_NOTICE: '仲裁庭通知', AGREEMENT: '约定期限', INTERNAL_TARGET: '内部目标' }
const deadlineLabels = { PROPOSED: '待核验', VERIFIED: '已核验', COMPLETED: '已完成', WAIVED: '已豁免' }
const emptyEvidence = () => ({ id: '', version: 1, objectReference: '', sha256: '', sourceType: 'CLIENT_SUBMITTED', issueIds: '' })
const emptyDeadline = () => ({ id: '', dueAt: '', zoneId: 'Asia/Shanghai', basis: 'INTERNAL_TARGET', basisReference: '' })

export default function MatterMaterials({ view, command, busy, error, canVerify = false }) {
  const [mode, setMode] = useState(null)
  const [evidence, setEvidence] = useState(emptyEvidence)
  const [deadline, setDeadline] = useState(emptyDeadline)
  const [validation, setValidation] = useState('')
  const [discard, setDiscard] = useState(false)
  const [uploadBusy, setUploadBusy] = useState(false)
  const [stored, setStored] = useState(null)
  const working = busy || uploadBusy
  const latestEvidence = new Map()
  for (const item of view.evidence || []) {
    if (!latestEvidence.has(item.id) || item.version > latestEvidence.get(item.id)) latestEvidence.set(item.id, item.version)
  }
  const locked = ['SIGNED_OFF', 'CLOSED', 'REJECTED'].includes(view.matter.status)
  const open = kind => { setValidation(''); setMode(kind) }
  const dirty = ['preserve', 'verify'].includes(mode) ? false : mode === 'evidence' ? Boolean(evidence.id || evidence.objectReference || evidence.sha256 || evidence.issueIds) : Boolean(deadline.id || deadline.dueAt || deadline.basisReference)
  const close = () => { if (working) return; if (dirty) setDiscard(true); else setMode(null) }
  const reset = () => { setMode(null); setDiscard(false); setEvidence(emptyEvidence()); setDeadline(emptyDeadline()); setValidation(''); setStored(null) }
  const save = async () => {
    setValidation('')
    if (mode === 'evidence') {
      if (!evidence.id.trim() || !evidence.objectReference.trim() || !/^[a-f0-9]{64}$/i.test(evidence.sha256.trim()) || !Number.isInteger(Number(evidence.version)) || Number(evidence.version) < 1) {
        setValidation('请填写材料编号、存储引用、正整数版本及 64 位十六进制 SHA-256。'); return
      }
      if (await command('evidence', { ...evidence, id: evidence.id.trim(), objectReference: evidence.objectReference.trim(), sha256: evidence.sha256.trim().toLowerCase(), version: Number(evidence.version), preservationStatus: 'UNVERIFIED', issueIds: [...new Set(evidence.issueIds.split(/[,，\n]/).map(value => value.trim()).filter(Boolean))] }, '材料已登记。材料变更后的报告需要重新复核。')) reset()
    } else {
      if (!deadline.id.trim() || !deadline.basisReference.trim()) {
        setValidation('请填写期限编号和权威依据引用。'); return
      }
      let dueAt
      try { dueAt = normalizeDeadlineTime(deadline.dueAt, deadline.zoneId) }
      catch (failure) { setValidation(failure.message); return }
      if (await command('deadlines', { ...deadline, id: deadline.id.trim(), basisReference: deadline.basisReference.trim(), dueAt }, '期限已登记，待人工核验。')) reset()
    }
  }
  return <Stack spacing={2}>
    <SectionCard title="证据材料" action={<Button disabled={working || locked} onClick={() => open('evidence')}>登记材料</Button>}>
      <Stack spacing={2}><Alert severity="info">可上传文件后登记，也可填写已存储材料的引用和摘要。请先核对原件与保管记录；手工引用不会自动验证对象是否存在。</Alert>
        {!view.evidence?.length && <Typography color="text.secondary">暂无登记材料。</Typography>}
        {view.evidence?.map(item => <Box key={`${item.id}-${item.version}`} sx={{ p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 2, overflowWrap: 'anywhere' }}><Stack spacing={1}>
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center"><Typography fontWeight={700}>{item.id} · V{item.version}</Typography>{item.version !== latestEvidence.get(item.id) && <Chip size="small" label="历史版本" />}<Chip size="small" label={preservation[item.preservationStatus] || item.preservationStatus} /></Stack>
          <Typography variant="body2">{sources[item.sourceType] || item.sourceType} · 保管人：{item.custodian}</Typography><Typography variant="body2">存储引用：{item.objectReference}</Typography><Typography variant="caption">SHA-256：{item.sha256}</Typography>
          <Typography variant="caption">取得时间：{formatTime(item.acquiredAt)} · 争点：{item.issueIds?.join('、') || '未关联'}</Typography>
          {item.version === latestEvidence.get(item.id) && !['PRESERVED', 'RELEASED'].includes(item.preservationStatus) && <Button sx={{ alignSelf: 'flex-start' }} disabled={working || locked} onClick={() => { setEvidence({ ...emptyEvidence(), id: item.id }); open('preserve') }}>确认保全记录</Button>}
        </Stack></Box>)}
      </Stack>
    </SectionCard>
    <SectionCard title="期限管理" action={<Button disabled={working || locked} onClick={() => open('deadline')}>登记期限</Button>}><Stack spacing={2}>
      <Alert severity="warning">期限须依据权威来源人工核验；当前页面不提供自动提醒或送达期限计算。</Alert>
      {!view.deadlines?.length && <Typography color="text.secondary">暂无登记期限。</Typography>}
      {view.deadlines?.map(item => <Box key={item.id} sx={{ p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 2, overflowWrap: 'anywhere' }}><Stack spacing={1}>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap"><Typography fontWeight={700}>{item.id}</Typography><Chip size="small" label={deadlineLabels[item.status] || item.status} /></Stack>
        <Typography variant="body2">办理时区截止时间：{formatDeadlineInZone(item.dueAt, item.zoneId)}</Typography><Typography variant="caption" color="text.secondary">UTC：{item.dueAt}</Typography><Typography variant="body2">办理时区：{item.zoneId} · {bases[item.basis] || item.basis}</Typography><Typography variant="body2">依据：{item.basisReference}</Typography>
        {item.verifiedBy && <Typography variant="caption">核验人：{item.verifiedBy} · {formatTime(item.verifiedAt)}</Typography>}
        {item.status === 'PROPOSED' && !canVerify && <Typography variant="caption" color="text.secondary">期限核验需要律师身份。</Typography>}{item.status === 'PROPOSED' && <Button disabled={working || locked || !canVerify} sx={{ alignSelf: 'flex-start' }} onClick={() => { setDeadline({ ...emptyDeadline(), ...item }); open('verify') }}>核验期限</Button>}
      </Stack></Box>)}
    </Stack></SectionCard>
    <Dialog open={Boolean(mode)} onClose={close} fullWidth maxWidth="sm"><DialogTitle>{{ evidence: '登记材料元数据', deadline: '登记办理期限', preserve: '确认材料保全', verify: '人工核验期限' }[mode]}</DialogTitle><DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
      {mode === 'evidence' && <><MatterFileUpload matterId={view.matter.id} disabled={busy} stored={stored} onBusy={setUploadBusy} onStored={(result, fileName) => { setStored({ ...result, fileName }); setEvidence(current => ({ ...current, id: current.id.trim() || result.documentId, objectReference: result.objectUri, sha256: result.versionHash })); setValidation('') }} /><Alert severity="warning">登记新材料版本会影响报告审核有效性，请核对后提交。</Alert>{[['id', '材料编号'], ['version', '版本'], ['objectReference', '存储引用'], ['sha256', 'SHA-256 摘要'], ['issueIds', '关联争点编号（逗号分隔）']].map(([key, label]) => <TextField key={key} label={label} required={key !== 'issueIds'} disabled={working} InputProps={{ readOnly: Boolean(stored && ['objectReference', 'sha256'].includes(key)) }} type={key === 'version' ? 'number' : 'text'} value={evidence[key]} onChange={event => setEvidence({ ...evidence, [key]: event.target.value })} />)}<TextField select label="材料来源" value={evidence.sourceType} onChange={event => setEvidence({ ...evidence, sourceType: event.target.value })}>{Object.entries(sources).map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}</TextField></>}
      {mode === 'deadline' && <>{[['id', '期限编号'], ['dueAt', '截止时间（含 Z 或 +08:00 等偏移）'], ['zoneId', '办理时区（IANA）'], ['basisReference', '权威依据 / 通知引用']].map(([key, label]) => <TextField key={key} required label={label} value={deadline[key]} onChange={event => setDeadline({ ...deadline, [key]: event.target.value })} helperText={key === 'dueAt' ? '例如：2026-12-01T17:00:00+08:00' : undefined} />)}<TextField select label="期限依据" value={deadline.basis} onChange={event => setDeadline({ ...deadline, basis: event.target.value })}>{Object.entries(bases).map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}</TextField></>}
      {mode === 'preserve' && <Alert severity="warning">确认已核对材料 {evidence.id} 的保全记录。此操作仅登记确认，不会执行外部证据保全。</Alert>}
      {mode === 'verify' && <Alert severity="warning">请确认已核对 {deadline.id} 的权威依据、截止时刻 {deadline.dueAt} 和办理时区 {deadline.zoneId}。核验身份由服务端确定。</Alert>}
      {(validation || error) && <Alert severity="error">{validation || error}</Alert>}
    </Stack></DialogContent><DialogActions><Button disabled={working} onClick={close}>取消</Button><Button variant="contained" disabled={working} onClick={async () => {
      if (mode === 'preserve' || mode === 'verify') {
        const path = mode === 'preserve' ? `evidence/${encodeURIComponent(evidence.id)}/preserve` : `deadlines/${encodeURIComponent(deadline.id)}/verify`
        if (await command(path, {}, mode === 'preserve' ? '保全记录已确认。' : '期限已核验。')) reset()
      } else await save()
    }}>{uploadBusy ? '文件上传中…' : busy ? '保存中…' : '确认提交'}</Button></DialogActions></Dialog>
    <Dialog open={discard} onClose={() => setDiscard(false)}><DialogTitle>放弃未提交内容？</DialogTitle><DialogContent>关闭后不会保留本次填写的材料或期限内容。{stored && '已上传文件仍保留在资料区，尚未成为本次事项的证据登记记录。请先复制存储引用和摘要以便后续登记。'}</DialogContent><DialogActions><Button onClick={() => setDiscard(false)}>继续编辑</Button><Button color="warning" onClick={reset}>放弃并关闭</Button></DialogActions></Dialog>
  </Stack>
}
