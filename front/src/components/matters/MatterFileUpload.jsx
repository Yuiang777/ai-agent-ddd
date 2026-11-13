import { useEffect, useState } from 'react'
import { Alert, Box, Button, LinearProgress, Stack, Typography } from '@mui/material'
import { errorMessage, getDocumentUploadCapabilities, uploadMatterDocument } from '../../api/matters'

export default function MatterFileUpload({ matterId, disabled, stored, onStored, onBusy }) {
  const [file, setFile] = useState(null)
  const [capability, setCapability] = useState(null)
  const [capabilityError, setCapabilityError] = useState('')
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let active = true
    setCapability(null); setCapabilityError('')
    getDocumentUploadCapabilities().then(data => { if (active) setCapability(data) })
      .catch(failure => { if (active) setCapabilityError(errorMessage(failure)) })
    return () => { active = false }
  }, [reload])
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState(null)
  const [error, setError] = useState('')
  const upload = async () => {
    if (!file || busy || !capability?.enabled) return
    setBusy(true); onBusy(true); setError(''); setProgress(null)
    try { onStored(await uploadMatterDocument(matterId, file, setProgress), file.name) }
    catch (failure) { setError(errorMessage(failure) === 'LEGAL_DOCUMENT_CATALOG_DISABLED' ? '当前环境未启用文档存储目录，无法上传；请启用存储基础设施，或登记已有材料引用' : errorMessage(failure)) }
    finally { setBusy(false); onBusy(false) }
  }
  return <Box sx={{ p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 2 }}><Stack spacing={1.5}>
    <Typography fontWeight={700}>上传文件并填入登记信息</Typography>
    <Typography variant="body2" color="text.secondary">支持 PDF、Word、TXT、Markdown。文件进入当前事项私有资料区，服务端计算摘要；上传后仍需确认提交材料登记。</Typography>
    {!stored && !capability && !capabilityError && <Typography variant="body2">正在检查上传能力…</Typography>}
    {!stored && capability && <Alert severity={capability.enabled ? 'info' : 'warning'}>{capability.reason}{capability.enabled && ` 单文件上限 ${(capability.maxFileBytes / 1024 / 1024).toFixed(1)} MB；总请求上限 ${(capability.maxRequestBytes / 1024 / 1024).toFixed(1)} MB（包含表单数据）。`}</Alert>}
    {!stored && capabilityError && <Alert severity="warning" action={<Button onClick={() => setReload(value => value + 1)}>重试</Button>}>无法确认上传能力：{capabilityError}。可继续手工登记已有材料。</Alert>}
    {stored ? <Alert severity="success">{stored.fileName} 已存储，尚待本次材料登记提交。登记失败时可直接重试下方提交，无需再次上传。</Alert> : <>
      <Button component="label" variant="outlined" disabled={disabled || busy || !capability?.enabled}>选择文件<input hidden type="file" accept={capability?.allowedExtensions.map(value => `.${value}`).join(',') || '.pdf,.doc,.docx,.txt,.md'} onChange={event => {
        const chosen = event.target.files?.[0]
        event.target.value = ''
        setError('')
        if (chosen && (!capability?.allowedExtensions.includes(chosen.name.split('.').at(-1).toLowerCase()) || chosen.size === 0)) { setFile(null); setError('请选择非空的 PDF、Word、TXT 或 Markdown 文件'); return }
        if (chosen && chosen.size > capability.maxFileBytes) { setFile(null); setError('所选文件超过当前环境的单文件上限'); return }
        setFile(chosen || null)
      }} /></Button>
      {file && <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{file.name} · {(file.size / 1024).toFixed(1)} KB</Typography>}
      <Button disabled={disabled || busy || !file || !capability?.enabled} onClick={upload}>{busy ? '正在上传并保存…' : '上传到当前事项'}</Button>
    </>}
    {busy && <LinearProgress variant={progress === null ? 'indeterminate' : 'determinate'} value={progress || 0} aria-label="文件上传进度" />}
    {error && <Alert severity="error">{error}。若连接中断，文件可能已存储，请核对后重试。</Alert>}
  </Stack></Box>
}
